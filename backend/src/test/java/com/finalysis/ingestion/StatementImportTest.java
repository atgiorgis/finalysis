package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports through the API against Testcontainers Postgres. No test transaction: every import
 * commits, as in production; accounts created here are deleted after each test.
 */
@IntegrationTest
@AutoConfigureMockMvc
class StatementImportTest {

    private static final String CHECKING_APRIL = "ofx/northfield-checking_2026-04.ofx";
    private static final String PROBLEM = "urn:finalysis:problem:";

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final ImportTestSupport support;

    StatementImportTest(MockMvc mockMvc, JsonMapper jsonMapper, AccountRepository accounts, JdbcTemplate jdbc,
                        Environment environment) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.environment = environment;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    // ------------------------------------------------------------------ sample data end to end

    @Test
    void everySampleOfxFileImportsAndMatchesTheAnswerKey() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long apex = support.account(AccountType.CREDIT_CARD, "2001");
        List<Path> files;
        try (Stream<Path> list = Files.list(ImportTestSupport.SAMPLE_DATA.resolve("ofx"))) {
            files = list.sorted().toList();
        }
        assertThat(files).hasSize(13);

        for (Path file : files) {
            String name = file.getFileName().toString();
            List<String[]> expected = ImportTestSupport.answerKey("statements/" + name.replaceAll("\\.(ofx|qfx)$", ".csv"));
            long account = name.startsWith("apex") ? apex : checking;

            JsonNode summary = json(support.upload(account, name, Files.readAllBytes(file))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", containsString("/api/statements/")))
                    .andReturn());
            long statementId = summary.get("statementId").asLong();

            assertThat(summary.get("parser").asString()).isEqualTo("ofx");
            assertThat(summary.get("parsed").asInt()).as(name).isEqualTo(expected.size());
            assertThat(summary.get("inserted").asInt()).as(name).isEqualTo(expected.size());
            assertThat(summary.get("duplicates").asInt()).isZero();
            assertThat(summary.get("status").asString()).isEqualTo("IMPORTED");
            assertThat(summary.get("warnings")).isEmpty();
            assertThat(jdbc.queryForObject("SELECT sum(amount) FROM txn WHERE statement_id = ?", BigDecimal.class, statementId))
                    .as(name).isEqualByComparingTo(ImportTestSupport.sum(expected));
        }
        assertThat(support.count("SELECT count(*) FROM txn WHERE account_id IN (?, ?)", checking, apex))
                .isEqualTo(Stream.of("northfield-checking", "apex-visa")
                        .mapToInt(slug -> answerKeyRowsFor(slug)).sum());
    }

    @Test
    void importedRowsKeepRawValuesAndSourceTracing() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long statementId = importOk(checking, "april.ofx", ImportTestSupport.sample(CHECKING_APRIL)).get("statementId").asLong();

        Map<String, Object> rent = jdbc.queryForMap("""
                SELECT raw_description, amount, printed_amount, external_id, source_section, source_line_number,
                       source_line_text, txn_date, post_date, merchant, fingerprint
                FROM txn WHERE statement_id = ? ORDER BY source_line_number LIMIT 1""", statementId);
        Map<String, Object> statement = jdbc.queryForMap(
                "SELECT parser, source_file_name, file_hash, status, closing_balance FROM statement WHERE id = ?", statementId);

        assertThat(rent).containsEntry("raw_description", "PARKSIDE PROPERTY MGMT RENT WEB PMT")
                .containsEntry("amount", new BigDecimal("-1850.00"))
                .containsEntry("printed_amount", "-1850.00")
                .containsEntry("external_id", "NF2026040100013")
                .containsEntry("source_section", "WITHDRAWAL")
                .containsEntry("source_line_number", 43)
                .containsEntry("merchant", null);
        assertThat((String) rent.get("source_line_text")).startsWith("<STMTTRN>").endsWith("</STMTTRN>");
        assertThat((String) rent.get("fingerprint")).hasSize(64);
        assertThat(statement).containsEntry("parser", "ofx").containsEntry("source_file_name", "april.ofx")
                .containsEntry("status", "IMPORTED").containsEntry("closing_balance", new BigDecimal("4066.58"));

        mockMvc.perform(get("/api/statements/{id}", statementId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(statementId))
                .andExpect(jsonPath("$.accountId").value(checking))
                .andExpect(jsonPath("$.parser").value("ofx"))
                .andExpect(jsonPath("$.periodStart").value("2026-04-01"))
                .andExpect(jsonPath("$.periodEnd").value("2026-04-30"))
                .andExpect(jsonPath("$.openingBalance").isEmpty())
                .andExpect(jsonPath("$.closingBalance").value(4066.58))
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andExpect(jsonPath("$.transactionCount").value(10))
                .andExpect(jsonPath("$.importedAt").isNotEmpty());
    }

    @Test
    void unknownStatementAndUnknownAccountAre404() throws Exception {
        mockMvc.perform(get("/api/statements/{id}", 999_999_999)).andExpect(status().isNotFound());
        support.upload(999_999_999, "a.ofx", ImportTestSupport.sample(CHECKING_APRIL)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ re-imports (ING-03, ING-04)

    @Test
    void sameBytesAgainIs409WithTheOriginalStatement() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        byte[] april = ImportTestSupport.sample(CHECKING_APRIL);
        long first = importOk(checking, "april.ofx", april).get("statementId").asLong();

        support.upload(checking, "renamed.ofx", april)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "already-imported"))
                .andExpect(jsonPath("$.statementId").value(first));
        assertThat(support.count("SELECT count(*) FROM statement WHERE account_id = ?", checking)).isEqualTo(1);
    }

    @Test
    void sameTransactionsInDifferentBytesInsertNothingAndWarnAboutTheOverlap() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        String april = new String(ImportTestSupport.sample(CHECKING_APRIL), StandardCharsets.ISO_8859_1);
        long first = importOk(checking, "april.ofx", bytes(april)).get("statementId").asLong();

        // A later download of the same statement: only the server timestamp differs.
        JsonNode second = importOk(checking, "april-again.ofx", bytes(april.replace("<DTSERVER>20260501080000", "<DTSERVER>20260502093000")));

        assertThat(second.get("parsed").asInt()).isEqualTo(10);
        assertThat(second.get("inserted").asInt()).isZero();
        assertThat(second.get("duplicates").asInt()).isEqualTo(10);
        assertThat(second.get("status").asString()).isEqualTo("IMPORTED");
        assertThat(strings(second.get("warnings"))).containsExactly("period overlaps statement " + first);
        assertThat(support.count("SELECT count(*) FROM txn WHERE account_id = ?", checking)).isEqualTo(10);
    }

    @Test
    void changedDetailsUnderAKnownFitidAreInsertedButNeedReview() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        String april = new String(ImportTestSupport.sample(CHECKING_APRIL), StandardCharsets.ISO_8859_1);
        importOk(checking, "april.ofx", bytes(april));

        String changed = april.replace("<DTSERVER>20260501080000", "<DTSERVER>20260502093000")
                .replace("<NAME>PARKSIDE PROPERTY MGMT RENT WEB", "<NAME>PARKSIDE PROPERTY MGMT RENT");
        JsonNode summary = importOk(checking, "april-changed.ofx", bytes(changed));

        assertThat(summary.get("inserted").asInt()).isEqualTo(1);
        assertThat(summary.get("duplicates").asInt()).isEqualTo(9);
        assertThat(summary.get("status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(strings(summary.get("warnings"))).contains(
                "line 43: possible duplicate: an earlier import has a row with the same transaction id (FITID) but different details");
    }

    @Test
    void parserWarningsNeedReview() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        JsonNode summary = importOk(checking, "x.ofx", ImportTestSupport.sample("edge-cases/ofx-trntype-contradicts-sign.ofx"));

        assertThat(summary.get("status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(strings(summary.get("warnings"))).containsExactly(
                "line 43: TRNTYPE DEBIT with a positive amount", "line 51: TRNTYPE CREDIT with a negative amount");
    }

    @Test
    void mixingFormatsOnOneAccountWarns() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        jdbc.update("""
                INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash, parser)
                VALUES (?, DATE '2026-03-01', DATE '2026-03-31', 'march.csv', 'hash-march-csv', 'csv')""", checking);

        JsonNode summary = importOk(checking, "april.ofx", ImportTestSupport.sample(CHECKING_APRIL));

        assertThat(strings(summary.get("warnings"))).containsExactly(
                "account mixes import formats (ofx and csv); rows imported in different formats are not recognized as duplicates");
        assertThat(summary.get("status").asString()).isEqualTo("IMPORTED");
    }

    // ------------------------------------------------------------------ request values

    @Test
    void requestPeriodOverridesAndBalancesFillGaps() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        JsonNode summary = importOk(checking, "april.ofx", ImportTestSupport.sample(CHECKING_APRIL), Map.of(
                "periodStart", "2026-03-31", "openingBalance", "3200.00", "closingBalance", "4000.00"));
        long id = summary.get("statementId").asLong();

        assertThat(summary.get("periodStart").asString()).isEqualTo("2026-03-31");
        assertThat(summary.get("status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(strings(summary.get("warnings")))
                .containsExactly("closingBalance in the request differs from the file; the file's value was kept");
        assertThat(jdbc.queryForMap("SELECT opening_balance, closing_balance FROM statement WHERE id = ?", id))
                .containsEntry("opening_balance", new BigDecimal("3200.00"))
                .containsEntry("closing_balance", new BigDecimal("4066.58"));
    }

    @Test
    void invalidRequestValuesAre400AndWriteNothing() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        support.upload(checking, "april.ofx", ImportTestSupport.sample(CHECKING_APRIL),
                        Map.of("periodEnd", "2026-03-01", "openingBalance", "1.005"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("periodEnd")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("openingBalance")));
        support.upload(checking, "april.ofx", ImportTestSupport.sample(CHECKING_APRIL), Map.of("periodStart", "April"))
                .andExpect(status().isBadRequest());
        assertThat(support.count("SELECT count(*) FROM statement WHERE account_id = ?", checking)).isZero();
    }

    // ------------------------------------------------------------------ rejections

    @Test
    void parseFailureWritesNothing() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        String april = new String(ImportTestSupport.sample(CHECKING_APRIL), StandardCharsets.ISO_8859_1);
        // The last row fails after nine valid ones.
        int last = april.lastIndexOf("<TRNAMT>");
        String broken = april.substring(0, last) + "<TRNAMT>-1,50" + april.substring(april.indexOf("\r\n", last));

        support.upload(checking, "broken.ofx", bytes(broken))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEM + "parse-error"))
                .andExpect(jsonPath("$.detail").value("line 115: invalid amount in <TRNAMT>"))
                .andExpect(jsonPath("$.line").value(115));
        assertThat(support.count("SELECT count(*) FROM statement WHERE account_id = ?", checking)).isZero();
        assertThat(support.count("SELECT count(*) FROM txn WHERE account_id = ?", checking)).isZero();
    }

    @Test
    void statementForAnotherAccountIsRejected() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long otherChecking = support.account(AccountType.CHECKING, "9999");

        support.upload(checking, "apex.qfx", ImportTestSupport.sample("ofx/apex-visa_2026-05.qfx"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEM + "account-mismatch"))
                .andExpect(jsonPath("$.detail").value("The file is a CREDIT_CARD statement, but account " + checking + " is CHECKING"));
        support.upload(otherChecking, "april.ofx", ImportTestSupport.sample(CHECKING_APRIL))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEM + "account-mismatch"));
        assertThat(support.count("SELECT count(*) FROM statement WHERE account_id IN (?, ?)", checking, otherChecking)).isZero();
    }

    @Test
    void nonUsdStatementIsRejected() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        String eur = new String(ImportTestSupport.sample(CHECKING_APRIL), StandardCharsets.ISO_8859_1).replace("<CURDEF>USD", "<CURDEF>EUR");

        support.upload(checking, "eur.ofx", bytes(eur))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEM + "unsupported-currency"));
    }

    @Test
    void xxeFileIsRejectedAsAParseError() throws Exception {
        long apex = support.account(AccountType.CREDIT_CARD, "2001");

        support.upload(apex, "x.ofx", ImportTestSupport.sample("edge-cases/ofx-xxe.ofx"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEM + "parse-error"))
                .andExpect(jsonPath("$.detail", containsString("DTD declarations")));
    }

    static Stream<Arguments> unsupportedFiles() {
        byte[] random = new byte[4096];
        new Random(7).nextBytes(random);
        return Stream.of(
                Arguments.of("statement.pdf", "%PDF-1.7\n1 0 obj".getBytes(StandardCharsets.US_ASCII), "must end in"),
                Arguments.of("statement.txt", "%PDF-1.7\n1 0 obj".getBytes(StandardCharsets.US_ASCII), "PDF statements"),
                Arguments.of("statement.csv", random, "not a text file"),
                Arguments.of("statement.csv", new byte[] {'P', 'K', 3, 4, 20, 0}, "ZIP and spreadsheet"),
                Arguments.of("statement.csv", "Date,Description,Amount\n04/01/2026,CAFE,-4.50\n".getBytes(StandardCharsets.US_ASCII),
                        "not in a supported statement format"),
                Arguments.of("statement.exe", "OFXHEADER:100\n".getBytes(StandardCharsets.US_ASCII), "must end in"),
                Arguments.of("statement", "OFXHEADER:100\n".getBytes(StandardCharsets.US_ASCII), "must end in"),
                Arguments.of("statement.ofx", new byte[0], "empty"),
                Arguments.of("statement.ofx", "OFXHEADER:100\n\u0000".getBytes(StandardCharsets.US_ASCII), "not a text file"));
    }

    @ParameterizedTest
    @MethodSource("unsupportedFiles")
    void unsupportedFilesAre415(String fileName, byte[] bytes, String detail) throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        support.upload(checking, fileName, bytes)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value(PROBLEM + "unsupported-format"))
                .andExpect(jsonPath("$.detail", containsString(detail)));
    }

    @Test
    void filesOverTenMegabytesAre413() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        support.upload(checking, "big.csv", new byte[10 * 1024 * 1024 + 1])
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.type").value(PROBLEM + "payload-too-large"));
    }

    @Test
    void multipartLimitsKeepUploadsInMemory() {
        DataSize maxFile = environment.getProperty("spring.servlet.multipart.max-file-size", DataSize.class);
        DataSize threshold = environment.getProperty("spring.servlet.multipart.file-size-threshold", DataSize.class);

        assertThat(maxFile).isEqualTo(DataSize.ofMegabytes(10));
        assertThat(maxFile.toBytes()).isEqualTo(UploadCheck.MAX_BYTES);
        assertThat(threshold.toBytes()).isGreaterThan(maxFile.toBytes());
    }

    @Test
    void errorResponsesNeverContainDescriptionsOrAccountNumbers() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long wrongAccount = support.account(AccountType.CHECKING, "9999");
        String marked = new String(ImportTestSupport.sample(CHECKING_APRIL), StandardCharsets.ISO_8859_1)
                .replace("<NAME>PARKSIDE PROPERTY MGMT RENT WEB", "<NAME>MARKER-DESCRIPTION-7Q &nbsp;");

        List<MvcResult> results = List.of(
                support.upload(checking, "marked.ofx", bytes(marked)).andReturn(),
                support.upload(wrongAccount, "april.ofx", ImportTestSupport.sample(CHECKING_APRIL)).andReturn(),
                support.upload(checking, "statement.txt", bytes("MARKER-DESCRIPTION-7Q,0047391001\n")).andReturn());

        for (MvcResult result : results) {
            String body = result.getResponse().getContentAsString();
            assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
            assertThat(body).doesNotContain("MARKER").doesNotContain("0047391001");
        }
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode importOk(long accountId, String fileName, byte[] bytes) throws Exception {
        return importOk(accountId, fileName, bytes, Map.of());
    }

    private JsonNode importOk(long accountId, String fileName, byte[] bytes, Map<String, String> params) throws Exception {
        return json(support.upload(accountId, fileName, bytes, params).andExpect(status().isCreated()).andReturn());
    }

    private JsonNode json(MvcResult result) throws IOException {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static int answerKeyRowsFor(String accountSlug) {
        try {
            return (int) Files.readAllLines(ImportTestSupport.SAMPLE_DATA.resolve("answer-key.csv")).stream()
                    .skip(1).filter(line -> line.split(",", -1)[1].equals(accountSlug)).count();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
