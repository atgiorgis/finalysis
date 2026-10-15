package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.categorize.MerchantCleaningService.CleaningResult;
import com.finalysis.ingestion.StatementImported;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Merchant cleaning against the database (docs/plans/phase-4.md §1.2, §2.4): after every import,
 * and the startup backfill with its re-clean when the rules change. Uses the synthetic fixture
 * {@code sample-data/edge-cases/merchants/}.
 */
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class MerchantBackfillTest {

    private static final String MERCHANTS = "edge-cases/merchants/";
    private static final String STATEMENT = "merchants-northfield-checking_2026-05.csv";
    private static final Map<String, String> MAY = Map.of("periodStart", "2026-05-01", "periodEnd", "2026-05-31");

    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final ImportTestSupport support;
    private final MerchantCleaningRules rules;
    private final MerchantEmbeddingRepository embeddings;

    /** Calls the real service unless a test says otherwise; reset after each test. */
    @MockitoSpyBean
    private MerchantCleaningService cleaning;

    MerchantBackfillTest(MockMvc mockMvc, JsonMapper jsonMapper, AccountRepository accounts, JdbcTemplate jdbc,
                         MerchantCleaningRules rules, MerchantEmbeddingRepository embeddings) {
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.rules = rules;
        this.embeddings = embeddings;
    }

    @AfterEach
    void cleanUp() {
        reset(cleaning);
        support.deleteCreatedAccounts();
        jdbc.update("DELETE FROM merchant_embedding WHERE merchant LIKE 'TEST %'");
        // Leave the recorded rules as startup left them, for the tests that follow.
        cleaning.backfill();
    }

    private record Expected(String description, String merchant, String counterparty) {}

    private record Row(long id, String rawDescription, String merchant, String counterparty, String fingerprint) {}

    @Test
    void anImportGetsMerchantsAndCounterpartiesAsTheFixtureExpects() throws Exception {
        long statementId = importFixture();

        List<Row> rows = rows(statementId);
        List<Expected> expected = expected();
        assertThat(rows).hasSameSizeAs(expected);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            Expected want = expected.get(i);
            assertThat(row.rawDescription()).as("raw description stays as extracted").isEqualTo(want.description());
            assertThat(row.merchant()).as(want.description()).isEqualTo(want.merchant());
            assertThat(row.counterparty()).as(want.description()).isEqualTo(want.counterparty());
        }
        assertThat(rows).extracting(Row::rawDescription).contains("MAPLEWOOD GROCERY\n#214 SAMPLETOWN VA");
    }

    @Test
    void theStartupBackfillFillsRowsWithoutAMerchantAndChangesNothingElse() throws Exception {
        long statementId = importFixture();
        List<Row> cleaned = rows(statementId);
        jdbc.update("UPDATE txn SET merchant = NULL, counterparty = NULL WHERE statement_id = ?", statementId);
        jdbc.update("UPDATE txn SET merchant = 'STALE' WHERE id = ?", cleaned.getFirst().id());

        CleaningResult result = cleaning.backfill();

        assertThat(result.fullReclean()).as("the rules haven't changed").isFalse();
        List<Row> after = rows(statementId);
        assertThat(after.subList(1, after.size())).isEqualTo(cleaned.subList(1, cleaned.size()));
        assertThat(after.getFirst().merchant()).as("a row with a merchant isn't re-cleaned").isEqualTo("STALE");
        assertThat(after).extracting(Row::rawDescription, Row::fingerprint)
                .as("raw description and fingerprint untouched")
                .isEqualTo(cleaned.stream().map(r -> tuple(r.rawDescription(), r.fingerprint())).toList());
        assertThat(cleaning.backfill().updated()).as("a second pass writes nothing").isZero();
    }

    @Test
    void changedRulesReCleanEveryRowAndRemoveUnusedEmbeddings() throws Exception {
        long statementId = importFixture();
        List<Row> cleaned = rows(statementId);
        jdbc.update("UPDATE txn SET merchant = 'STALE', counterparty = 'STALE' WHERE statement_id = ?", statementId);
        jdbc.update("UPDATE app_setting SET value = 'earlier rules' WHERE key = ?", MerchantCleaningService.RULES_SETTING);
        float[] vector = new float[MerchantEmbeddingRepository.DIMENSIONS];
        Arrays.fill(vector, 0.5f);
        String hash = MerchantEmbeddingStage.sha256("ANY TEXT");
        embeddings.upsert("TEST UNUSED MERCHANT", "test-model", hash, vector);
        embeddings.upsert("CORNER BEAN CAFE", "test-model", hash, vector); // used by the fixture

        CleaningResult result = cleaning.backfill();

        assertThat(result.fullReclean()).isTrue();
        assertThat(result.orphanEmbeddingsRemoved()).isGreaterThanOrEqualTo(1);
        assertThat(rows(statementId)).isEqualTo(cleaned);
        assertThat(embeddings.find("TEST UNUSED MERCHANT", "test-model")).isEmpty();
        assertThat(embeddings.find("CORNER BEAN CAFE", "test-model")).isPresent();
        assertThat(recordedRules()).isEqualTo(rules.fingerprint());
        assertThat(cleaning.backfill().fullReclean()).as("recorded, so the next start doesn't re-clean").isFalse();
        jdbc.update("DELETE FROM merchant_embedding WHERE merchant = 'CORNER BEAN CAFE'");
    }

    @Test
    void startupRecordedTheShippedRules() {
        assertThat(recordedRules()).isEqualTo(rules.fingerprint());
    }

    @Test
    void aCleaningFailureLeavesTheImportAndLogsNoStatementData(CapturedOutput output) throws Exception {
        doThrow(new IllegalStateException("MAPLEWOOD GROCERY -86.42")).when(cleaning).cleanStatement(anyLong());

        long statementId = importFixture();

        assertThat(support.count("SELECT count(*) FROM txn WHERE statement_id = ?", statementId)).isEqualTo(14);
        assertThat(output.getOut()).contains("Merchant cleaning after the import of statement " + statementId + " failed")
                .contains("java.lang.IllegalStateException")
                .doesNotContain("MAPLEWOOD").doesNotContain("86.42");
    }

    /** Categorization needs to know which txns are linked, so it reacts to the import after detection. */
    @Test
    void cleaningRunsAfterTransferDetection() throws Exception {
        Method categorization = CategorizationListener.class.getDeclaredMethod("onImported",
                StatementImported.class);
        Method detection = Class.forName("com.finalysis.transfer.TransferDetectionListener")
                .getDeclaredMethod("onImported", StatementImported.class);

        assertThat(detection.getAnnotation(Order.class).value())
                .isLessThan(categorization.getAnnotation(Order.class).value());
    }

    private long importFixture() throws Exception {
        long account = support.account(AccountType.CHECKING, "7731");
        byte[] bytes = ImportTestSupport.sample(MERCHANTS + STATEMENT);
        JsonNode preview = json(support.preview(account, STATEMENT, bytes).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        support.putMapping(account, preview.get("suggestedMapping").get("mapping").toString())
                .andExpect(status().isCreated());
        return json(support.upload(account, STATEMENT, bytes, MAY).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString()).get("statementId").asLong();
    }

    private List<Row> rows(long statementId) {
        return jdbc.query("""
                SELECT id, raw_description, merchant, counterparty, fingerprint FROM txn
                WHERE statement_id = ? ORDER BY source_line_number""",
                (rs, n) -> new Row(rs.getLong("id"), rs.getString("raw_description"), rs.getString("merchant"),
                        rs.getString("counterparty"), rs.getString("fingerprint")), statementId);
    }

    private String recordedRules() {
        return jdbc.queryForObject("SELECT value FROM app_setting WHERE key = ?", String.class,
                MerchantCleaningService.RULES_SETTING);
    }

    private static List<Expected> expected() throws IOException {
        String csv = new String(ImportTestSupport.sample(MERCHANTS + "merchants-expected.csv"), StandardCharsets.UTF_8);
        List<Expected> expected = new ArrayList<>();
        try (Reader reader = new StringReader(csv)) {
            for (CSVRecord r : CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
                expected.add(new Expected(r.get("description"), emptyToNull(r.get("merchant")),
                        emptyToNull(r.get("counterparty"))));
            }
        }
        return expected;
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private JsonNode json(String body) {
        return jsonMapper.readTree(body);
    }
}
