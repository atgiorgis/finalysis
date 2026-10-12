package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.ingestion.csv.CsvMappingService;
import com.finalysis.ingestion.csv.CsvMappingSpec;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * CSV import end to end against Testcontainers Postgres (ING-01, ING-03, ING-04): previews and
 * suggested mappings, the mapping lifecycle, the whole sample data set against the answer key, and
 * the snapshot check that refuses an import whose mapping changed while it was parsed. No test
 * transaction: every request commits, as in production.
 */
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class CsvImportTest {

    private static final String PROBLEM = "urn:finalysis:problem:";
    private static final String CHECKING_APRIL = "statements/northfield-checking_2026-04.csv";
    private static final String CHECKING_MAY = "statements/northfield-checking_2026-05.csv";

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final ImportTestSupport support;

    /** Calls the real service unless a test says otherwise; reset after each test. */
    @MockitoSpyBean
    private CsvMappingService csvMappings;

    CsvImportTest(MockMvc mockMvc, JsonMapper jsonMapper, AccountRepository accounts, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    // ------------------------------------------------------------------ the whole sample data set

    record ManifestEntry(String file, String account, String start, String end) {
    }

    @Test
    void everySampleStatementImportsWithItsSuggestedMappingAndMatchesTheAnswerKey() throws Exception {
        Map<String, Long> csvAccounts = new LinkedHashMap<>();
        csvAccounts.put("northfield-checking", support.account(AccountType.CHECKING, "1001"));
        csvAccounts.put("northfield-savings", support.account(AccountType.SAVINGS, "1002"));
        csvAccounts.put("apex-visa", support.account(AccountType.CREDIT_CARD, "2001"));
        csvAccounts.put("summit-card", support.account(AccountType.CREDIT_CARD, "3001"));
        long ofxChecking = support.account(AccountType.CHECKING, "1001");
        long ofxApex = support.account(AccountType.CREDIT_CARD, "2001");

        List<ManifestEntry> manifest = manifest();
        assertThat(manifest).hasSize(25);
        for (ManifestEntry entry : manifest) {
            long account = csvAccounts.get(entry.account());
            if (support.count("SELECT count(*) FROM csv_mapping WHERE account_id = ?", account) == 0) {
                // The first month's suggestion, saved without edits, reads every month.
                saveSuggestedMapping(account, entry.file());
            }
            JsonNode summary = importOk(account, entry.file(), Map.of("periodStart", entry.start(), "periodEnd", entry.end()));
            assertStatementMatchesAnswerKey(summary, entry.file(), "csv");
            assertThat(summary.get("periodStart").asString()).isEqualTo(entry.start());
            assertThat(summary.get("periodEnd").asString()).isEqualTo(entry.end());
        }
        for (Path file : ofxFiles()) {
            String name = file.getFileName().toString();
            JsonNode summary = importOk(name.startsWith("apex") ? ofxApex : ofxChecking, "ofx/" + name, Map.of());
            assertStatementMatchesAnswerKey(summary, "statements/" + name.replaceAll("\\.(ofx|qfx)$", ".csv"), "ofx");
        }

        // Per account: every answer-key row once, and the same total.
        csvAccounts.forEach((slug, account) -> assertAccountMatchesAnswerKey(account, slug));
        assertAccountMatchesAnswerKey(ofxChecking, "northfield-checking");
        assertAccountMatchesAnswerKey(ofxApex, "apex-visa");
        assertThat(support.count("SELECT count(*) FROM csv_mapping WHERE account_id IN (?, ?, ?, ?) AND used_at IS NOT NULL",
                csvAccounts.values().toArray())).isEqualTo(4);
    }

    @Test
    void overlappingNorthfieldExportInsertsNothing() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        saveSuggestedMapping(checking, CHECKING_APRIL);
        long april = importOk(checking, CHECKING_APRIL, Map.of()).get("statementId").asLong();
        long may = importOk(checking, CHECKING_MAY, Map.of()).get("statementId").asLong();
        int rows = support.count("SELECT count(*) FROM txn WHERE account_id = ?", checking);

        JsonNode overlap = importOk(checking, "edge-cases/csv-northfield-checking_2026-04-15_2026-05-14.csv", Map.of());

        assertThat(overlap.get("parsed").asInt()).isPositive();
        assertThat(overlap.get("inserted").asInt()).isZero();
        assertThat(overlap.get("duplicates").asInt()).isEqualTo(overlap.get("parsed").asInt());
        assertThat(overlap.get("status").asString()).isEqualTo("IMPORTED");
        assertThat(strings(overlap.get("warnings"))).containsExactly(
                "period overlaps statement " + april + "; no new rows",
                "period overlaps statement " + may + "; no new rows");
        assertThat(support.count("SELECT count(*) FROM txn WHERE account_id = ?", checking)).isEqualTo(rows);
    }

    @Test
    void preambleFileImportsWithItsSuggestedMapping() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        JsonNode preview = preview(checking, "edge-cases/csv-preamble.csv");
        assertThat(preview.get("format").asString()).isEqualTo("csv");
        assertThat(preview.get("skipRows").asInt()).isEqualTo(5);
        support.putMapping(checking, preview.get("suggestedMapping").get("mapping").toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.skipRows").value(5));
        JsonNode summary = importOk(checking, "edge-cases/csv-preamble.csv", Map.of());

        assertStatementMatchesAnswerKey(summary, CHECKING_APRIL, "csv");
        // Line numbers count the skipped preamble: the first row is on line 7.
        assertThat(support.count("SELECT min(source_line_number) FROM txn WHERE account_id = ?", checking)).isEqualTo(7);
    }

    // ------------------------------------------------------------------ mapping lifecycle

    @Test
    void mappingLifecycle() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        // No mapping: 422 pointing to the preview, nothing written.
        upload(checking, CHECKING_APRIL)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEM + "mapping-required"))
                .andExpect(jsonPath("$.detail", containsString("POST /api/accounts/" + checking + "/imports/preview")));
        assertThat(support.count("SELECT count(*) FROM statement WHERE account_id = ?", checking)).isZero();

        // The suggestion saved as is; not used yet.
        JsonNode preview = preview(checking, CHECKING_APRIL);
        assertThat(preview.get("mappingStatus").asString()).isEqualTo("NONE");
        JsonNode suggested = preview.get("suggestedMapping").get("mapping");
        support.putMapping(checking, suggested.toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usedAt").isEmpty());

        // The first import uses it and sets used_at; the second reuses it, leaving used_at alone.
        importOk(checking, CHECKING_APRIL, Map.of());
        String usedAt = mapping(checking).get("usedAt").asString();
        assertThat(usedAt).isNotEmpty();
        assertThat(preview(checking, CHECKING_MAY).get("mappingStatus").asString()).isEqualTo("MATCHES");
        assertThat(preview(checking, CHECKING_MAY).has("suggestedMapping")).isFalse();
        importOk(checking, CHECKING_MAY, Map.of());
        assertThat(mapping(checking).get("usedAt").asString()).isEqualTo(usedAt);

        // Changed headers: 422 with the preview and a suggestion for the new headers.
        String june = new String(ImportTestSupport.sample("statements/northfield-checking_2026-06.csv"), StandardCharsets.UTF_8)
                .replaceFirst("Description", "Details");
        support.upload(checking, "june.csv", june.getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEM + "mapping-mismatch"))
                .andExpect(jsonPath("$.preview.format").value("csv"))
                .andExpect(jsonPath("$.preview.mappingStatus").value("MISMATCH"))
                .andExpect(jsonPath("$.preview.headers[1]").value("Details"))
                .andExpect(jsonPath("$.preview.rows.length()").value(10))
                .andExpect(jsonPath("$.preview.suggestedMapping.mapping.descriptionColumns[0]").value("Details"));
        assertThat(support.count("SELECT count(*) FROM statement WHERE account_id = ?", checking)).isEqualTo(2);

        // A locked field can't change silently once the mapping has been used.
        ObjectNode inverted = (ObjectNode) suggested.deepCopy();
        inverted.put("sign", "INVERTED");
        support.putMapping(checking, inverted.toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "mapping-locked"))
                .andExpect(jsonPath("$.fields[0]").value("sign"));
    }

    @Test
    void mappingChangedBetweenSnapshotAndLockIsRefusedAndWritesNothing() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        saveSuggestedMapping(checking, CHECKING_APRIL);
        // Right after the import took its snapshot, another request changes the mapping (on its own
        // thread and connection, as a concurrent PUT would) while the file is being parsed.
        doAnswer(invocation -> {
            CsvMappingSpec snapshot = (CsvMappingSpec) invocation.callRealMethod();
            CompletableFuture.runAsync(() -> jdbc.update(
                    "UPDATE csv_mapping SET balance_column = NULL, updated_at = now() WHERE account_id = ?", checking)).join();
            return snapshot;
        }).when(csvMappings).snapshot(checking);

        upload(checking, CHECKING_APRIL)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "mapping-changed"))
                .andExpect(jsonPath("$.detail", containsString("Retry the import")));
        assertThat(support.count("SELECT count(*) FROM statement WHERE account_id = ?", checking)).isZero();
        assertThat(support.count("SELECT count(*) FROM txn WHERE account_id = ?", checking)).isZero();
        assertThat(mapping(checking).get("usedAt").isNull()).isTrue();
        assertThat(mapping(checking).get("balanceColumn").isNull()).isTrue();

        // A retry reads the file with the current mapping.
        reset(csvMappings);
        importOk(checking, CHECKING_APRIL, Map.of());
    }

    // ------------------------------------------------------------------ preview

    @Test
    void previewWritesNothingAndNeverLogsContents(CapturedOutput output) throws Exception {
        long card = support.account(AccountType.CREDIT_CARD, "3001");
        byte[] marked = "Trans. Date,Merchant Name,Amount\n01-Apr-2026,QZX7MARKER CAFE,-4.50\n".getBytes(StandardCharsets.UTF_8);
        int statements = support.count("SELECT count(*) FROM statement");
        int txns = support.count("SELECT count(*) FROM txn");
        int mappings = support.count("SELECT count(*) FROM csv_mapping");

        JsonNode preview = json(support.preview(card, "marked.csv", marked).andExpect(status().isOk()).andReturn());
        // A mismatch response carries the preview too; it must not reach the log either.
        support.putMapping(card, preview.get("suggestedMapping").get("mapping").toString()).andExpect(status().isCreated());
        support.upload(card, "other.csv", "Date,Payee,Amount\n2026-04-01,QZX7MARKER DELI,-1.00\n".getBytes(StandardCharsets.UTF_8))
                .andExpect(jsonPath("$.type").value(PROBLEM + "mapping-mismatch"));

        assertThat(preview.get("rows").get(0).get(1).asString()).isEqualTo("QZX7MARKER CAFE");
        assertThat(support.count("SELECT count(*) FROM statement")).isEqualTo(statements);
        assertThat(support.count("SELECT count(*) FROM txn")).isEqualTo(txns);
        assertThat(support.count("SELECT count(*) FROM csv_mapping")).isEqualTo(mappings + 1); // the PUT above
        assertThat(output.getAll()).contains("Previewed a csv file for account " + card).doesNotContain("QZX7MARKER");
    }

    @Test
    void csvPreviewReportsTheDetectedLayout() throws Exception {
        long card = support.account(AccountType.CREDIT_CARD, "3001");

        support.preview(card, "summit.csv", ImportTestSupport.sample("statements/summit-card_2026-04.csv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("csv"))
                .andExpect(jsonPath("$.delimiter").value(","))
                .andExpect(jsonPath("$.encoding").value("UTF-8"))
                .andExpect(jsonPath("$.skipRows").value(0))
                .andExpect(jsonPath("$.headers[0]").value("Trans. Date"))
                .andExpect(jsonPath("$.rows.length()").value(10))
                .andExpect(jsonPath("$.rows[0][0]").value("01-Apr-2026"))
                .andExpect(jsonPath("$.mappingStatus").value("NONE"))
                .andExpect(jsonPath("$.suggestedMapping.mapping.dateFormat").value("dd-MMM-yyyy"))
                .andExpect(jsonPath("$.suggestedMapping.mapping.sign").value("AS_IS"))
                .andExpect(jsonPath("$.suggestedMapping.confidence.sign").value("GUESSED"))
                .andExpect(jsonPath("$.suggestedMapping.notes[0]").value(containsString("purchases look negative")))
                .andExpect(jsonPath("$.periodStart").doesNotExist())
                .andExpect(jsonPath("$.csv").doesNotExist());
    }

    @Test
    void ofxPreviewReportsPeriodAndCount() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        support.preview(checking, "april.ofx", ImportTestSupport.sample("ofx/northfield-checking_2026-04.ofx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("ofx"))
                .andExpect(jsonPath("$.periodStart").value("2026-04-01"))
                .andExpect(jsonPath("$.periodEnd").value("2026-04-30"))
                .andExpect(jsonPath("$.transactionCount").value(10))
                .andExpect(jsonPath("$.headers").doesNotExist())
                .andExpect(jsonPath("$.mappingStatus").doesNotExist());
        assertThat(support.count("SELECT count(*) FROM statement WHERE account_id = ?", checking)).isZero();
    }

    @Test
    void previewRunsTheUploadChecks() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        support.preview(checking, "statement.exe", ImportTestSupport.sample(CHECKING_APRIL))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value(PROBLEM + "unsupported-format"));
        support.preview(checking, "statement.csv", new byte[] {'P', 'K', 3, 4})
                .andExpect(status().isUnsupportedMediaType());
        support.preview(checking, "big.csv", new byte[10 * 1024 * 1024 + 1]).andExpect(status().is(413));
        support.preview(checking, "apex.qfx", ImportTestSupport.sample("ofx/apex-visa_2026-05.qfx"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEM + "account-mismatch"));
        support.preview(999_999_999, "a.csv", ImportTestSupport.sample(CHECKING_APRIL)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ request values

    @Test
    void aCsvWithoutRowsImportsAsAnEmptyStatementForTheRequestPeriod() throws Exception {
        long savings = support.account(AccountType.SAVINGS, "1002");
        saveSuggestedMapping(savings, "statements/northfield-savings_2026-04.csv");
        byte[] headerOnly = "Date,Description,Debit,Credit,Balance\n".getBytes(StandardCharsets.UTF_8);

        support.upload(savings, "empty.csv", headerOnly)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEM + "parse-error"));
        support.upload(savings, "empty.csv", headerOnly, Map.of("periodStart", "2026-10-01", "periodEnd", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("periodEnd"));
        JsonNode summary = json(support.upload(savings, "empty.csv", headerOnly,
                        Map.of("periodStart", "2026-10-01", "periodEnd", "2026-10-31"))
                .andExpect(status().isCreated()).andReturn());

        assertThat(summary.get("parsed").asInt()).isZero();
        assertThat(summary.get("periodStart").asString()).isEqualTo("2026-10-01");
        assertThat(summary.get("status").asString()).isEqualTo("IMPORTED");
    }

    // ------------------------------------------------------------------ helpers

    /** Previews the sample file and saves its suggested mapping unedited; returns the mapping sent. */
    private JsonNode saveSuggestedMapping(long accountId, String sampleFile) throws Exception {
        JsonNode suggested = preview(accountId, sampleFile).get("suggestedMapping");
        assertThat(strings(suggested.get("confidence").valueStream().toList())).doesNotContain("MISSING");
        support.putMapping(accountId, suggested.get("mapping").toString()).andExpect(status().isCreated());
        return suggested.get("mapping");
    }

    private JsonNode preview(long accountId, String sampleFile) throws Exception {
        return json(support.preview(accountId, Path.of(sampleFile).getFileName().toString(),
                ImportTestSupport.sample(sampleFile)).andExpect(status().isOk()).andReturn());
    }

    private ResultActions upload(long accountId, String sampleFile) throws Exception {
        return support.upload(accountId, Path.of(sampleFile).getFileName().toString(), ImportTestSupport.sample(sampleFile));
    }

    private JsonNode importOk(long accountId, String sampleFile, Map<String, String> params) throws Exception {
        return json(support.upload(accountId, Path.of(sampleFile).getFileName().toString(),
                ImportTestSupport.sample(sampleFile), params).andExpect(status().isCreated()).andReturn());
    }

    private JsonNode mapping(long accountId) throws Exception {
        return json(mockMvc.perform(get("/api/accounts/{id}/csv-mapping", accountId)).andExpect(status().isOk()).andReturn());
    }

    private void assertStatementMatchesAnswerKey(JsonNode summary, String statementFile, String parser) {
        List<String[]> expected = ImportTestSupport.answerKey(statementFile);
        long statementId = summary.get("statementId").asLong();
        assertThat(summary.get("parser").asString()).isEqualTo(parser);
        assertThat(summary.get("parsed").asInt()).as(statementFile).isEqualTo(expected.size());
        assertThat(summary.get("inserted").asInt()).as(statementFile).isEqualTo(expected.size());
        assertThat(summary.get("status").asString()).as(statementFile).isEqualTo("IMPORTED");
        assertThat(summary.get("warnings")).as(statementFile).isEmpty();
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM txn WHERE statement_id = ?", BigDecimal.class, statementId))
                .as(statementFile).isEqualByComparingTo(ImportTestSupport.sum(expected));
    }

    /** The account holds exactly the answer key's rows for the slug: same count, same total. */
    private void assertAccountMatchesAnswerKey(long accountId, String slug) {
        List<String[]> expected = answerKeyRows(slug);
        assertThat(support.count("SELECT count(*) FROM txn WHERE account_id = ?", accountId)).as(slug)
                .isEqualTo(expected.size());
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM txn WHERE account_id = ?", BigDecimal.class, accountId))
                .as(slug).isEqualByComparingTo(ImportTestSupport.sum(expected));
    }

    private static List<ManifestEntry> manifest() throws IOException {
        return Files.readAllLines(ImportTestSupport.SAMPLE_DATA.resolve("statements-manifest.csv")).stream().skip(1)
                .map(line -> line.split(",", -1))
                .filter(c -> c[0].startsWith("statements/"))
                .map(c -> new ManifestEntry(c[0], c[1], c[2], c[3]))
                .toList();
    }

    private static List<Path> ofxFiles() throws IOException {
        try (Stream<Path> files = Files.list(ImportTestSupport.SAMPLE_DATA.resolve("ofx"))) {
            return files.sorted().toList();
        }
    }

    private static List<String[]> answerKeyRows(String slug) {
        try {
            return Files.readAllLines(ImportTestSupport.SAMPLE_DATA.resolve("answer-key.csv")).stream().skip(1)
                    .map(line -> line.split(",", -1))
                    .filter(c -> c[1].equals(slug))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode json(MvcResult result) throws IOException {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> strings(JsonNode array) {
        return strings(array.valueStream().toList());
    }

    private static List<String> strings(List<JsonNode> nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.asString()));
        return values;
    }
}
