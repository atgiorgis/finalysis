package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.ingestion.pdf.PdfDocumentText;
import com.finalysis.ingestion.pdf.PdfStatementParser;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unparsed uploads through the HTTP endpoint (ING-13, docs/plans/phase-3b.md §4): a PDF no layout
 * parser recognizes is answered 202 Accepted and recorded, never as a statement. Re-uploads follow
 * the §4.3 table. Step 3 has no layout parser, so a test-only one, switched per test, stands in
 * for "a parser claims it now".
 */
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class UnparsedUploadImportTest {

    private static final String PROBLEM = "urn:finalysis:problem:";
    private static final String UNRECOGNIZED = "pdf/edge-cases/pdf-unrecognized-layout.pdf";
    private static final String NO_TEXT = "pdf/edge-cases/pdf-no-text.pdf";
    private static final String DETAIL = "No supported statement layout was recognized; nothing was imported. "
            + "The file is held for review.";

    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final SwitchablePdfParser testParser;
    private final ImportTestSupport support;

    UnparsedUploadImportTest(MockMvc mockMvc, JsonMapper jsonMapper, AccountRepository accounts, JdbcTemplate jdbc,
                             SwitchablePdfParser testParser) {
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.testParser = testParser;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
    }

    @AfterEach
    void reset() {
        testParser.mode = Mode.OFF;
        support.deleteCreatedAccounts();
    }

    // ------------------------------------------------------------------ the 202 response

    @Test
    void anUnrecognizedPdfIsAcceptedAndRecordedNotImported() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        int statementsBefore = support.count("SELECT count(*) FROM statement");

        JsonNode body = accepted(support.upload(checking, "april card.pdf", ImportTestSupport.sample(UNRECOGNIZED))
                .andExpect(header().doesNotExist("Location")));

        assertThat(body.get("accountId").asLong()).isEqualTo(checking);
        assertThat(body.get("reason").asString()).isEqualTo("UNRECOGNIZED_LAYOUT");
        assertThat(body.get("fileName").asString()).isEqualTo("april card.pdf");
        assertThat(body.get("detail").asString()).isEqualTo(DETAIL);
        assertThat(body.get("createdAt").asString()).isNotBlank();
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("id", "accountId", "reason", "fileName",
                "createdAt", "detail");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM unparsed_upload WHERE id = ?", body.get("id").asLong());
        assertThat(row).containsEntry("account_id", checking)
                .containsEntry("file_sha256", ImportFile.of("x", ImportTestSupport.sample(UNRECOGNIZED)).sha256())
                .containsEntry("file_name", "april card.pdf")
                .containsEntry("reason", "UNRECOGNIZED_LAYOUT")
                .containsEntry("resolved_at", null)
                .containsEntry("resolved_statement_id", null);
        assertThat(support.count("SELECT count(*) FROM statement")).isEqualTo(statementsBefore);
        assertThat(support.count("SELECT count(*) FROM txn WHERE account_id = ?", checking)).isZero();
    }

    @Test
    void aPdfWithoutATextLayerIsHeldAsNoTextLayer() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        JsonNode body = accepted(support.upload(checking, "scan.pdf", ImportTestSupport.sample(NO_TEXT)));

        assertThat(body.get("reason").asString()).isEqualTo("NO_TEXT_LAYER");
    }

    static Stream<Arguments> samplePdfs() throws IOException {
        return Files.readAllLines(ImportTestSupport.SAMPLE_DATA.resolve("pdf/pdf-manifest.csv")).stream().skip(1)
                .map(line -> line.split(",", -1))
                .map(c -> Arguments.of(c[0], c[1], c[2]));
    }

    /** With no layout parser yet, every sample PDF is held; the manifest's own reason where it names one. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("samplePdfs")
    void everySamplePdfIsHeldUntilALayoutParserExists(String file, String parser, String reason) throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        JsonNode body = accepted(support.upload(checking, Path.of(file).getFileName().toString(),
                ImportTestSupport.sample("pdf/" + file)));

        // Files marked boa-checking are recognized from step 4 and imported from step 7.
        assertThat(body.get("reason").asString()).isEqualTo(parser.equals("none") ? reason : "UNRECOGNIZED_LAYOUT");
    }

    @Test
    void logsCarryIdsAndTheReasonButNeverTheFileName(CapturedOutput output) throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        JsonNode body = accepted(support.upload(checking, "QZNAMEMARKER statement.pdf",
                ImportTestSupport.sample(UNRECOGNIZED)));

        assertThat(output).contains("unparsed upload " + body.get("id").asLong() + " (UNRECOGNIZED_LAYOUT)")
                .doesNotContain("QZNAMEMARKER").doesNotContain("APEX");
    }

    @Test
    void malformedBalancesAreRefusedEvenWhenNothingWouldBeImported() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        support.upload(checking, "card.pdf", ImportTestSupport.sample(UNRECOGNIZED), Map.of("openingBalance", "1e3"))
                .andExpect(status().isBadRequest());

        assertThat(openUploads(checking)).isZero();
    }

    // ------------------------------------------------------------------ re-uploads (§4.3)

    @Test
    void aReuploadThatIsStillUnrecognizedReturnsTheExistingUpload() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        byte[] pdf = ImportTestSupport.sample(UNRECOGNIZED);

        JsonNode first = accepted(support.upload(checking, "first.pdf", pdf));
        JsonNode second = accepted(support.upload(checking, "renamed.pdf", pdf));

        assertThat(second.get("id")).isEqualTo(first.get("id"));
        assertThat(second.get("createdAt")).isEqualTo(first.get("createdAt"));
        assertThat(second.get("fileName").asString()).isEqualTo("first.pdf");
        assertThat(support.count("SELECT count(*) FROM unparsed_upload WHERE account_id = ?", checking)).isEqualTo(1);
    }

    @Test
    void aReuploadThatIsRecognizedNowImportsAndResolvesTheUpload() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        byte[] pdf = ImportTestSupport.sample(UNRECOGNIZED);
        long uploadId = accepted(support.upload(checking, "card.pdf", pdf)).get("id").asLong();

        testParser.mode = Mode.CLAIM;
        JsonNode summary = json(support.upload(checking, "card.pdf", pdf)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parser").value(SwitchablePdfParser.ID))
                .andExpect(jsonPath("$.inserted").value(1)));

        long statementId = summary.get("statementId").asLong();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM unparsed_upload WHERE id = ?", uploadId);
        assertThat(row.get("resolved_at")).isNotNull();
        assertThat(row.get("resolved_statement_id")).isEqualTo(statementId);
        assertThat(openUploads(checking)).isZero();

        // The statement now blocks the file as usual.
        support.upload(checking, "card.pdf", pdf).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "already-imported"))
                .andExpect(jsonPath("$.statementId").value(statementId));
    }

    @Test
    void aRecognizedPdfWithoutAnOpenUploadImportsNormally() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        testParser.mode = Mode.CLAIM;

        support.upload(checking, "card.pdf", ImportTestSupport.sample(UNRECOGNIZED))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"));

        assertThat(support.count("SELECT count(*) FROM unparsed_upload WHERE account_id = ?", checking)).isZero();
    }

    @Test
    void aFailedImportLeavesTheUploadOpen() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        byte[] pdf = ImportTestSupport.sample(UNRECOGNIZED);
        long uploadId = accepted(support.upload(checking, "card.pdf", pdf)).get("id").asLong();

        testParser.mode = Mode.CLAIM_FOR_ANOTHER_ACCOUNT;
        support.upload(checking, "card.pdf", pdf).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEM + "account-mismatch"));
        testParser.mode = Mode.CLAIM_UNREADABLE;
        support.upload(checking, "card.pdf", pdf).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEM + "parse-error"))
                .andExpect(jsonPath("$.line").value(7));

        assertThat(openUploads(checking)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT resolved_at IS NULL FROM unparsed_upload WHERE id = ?", Boolean.class,
                uploadId)).isTrue();
        assertThat(support.count("SELECT count(*) FROM statement WHERE account_id = ?", checking)).isZero();
    }

    @Test
    void resolvedUploadsAreHistoryAndNeverReopen() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        byte[] pdf = ImportTestSupport.sample(UNRECOGNIZED);
        long firstId = accepted(support.upload(checking, "card.pdf", pdf)).get("id").asLong();
        testParser.mode = Mode.CLAIM;
        long statementId = json(support.upload(checking, "card.pdf", pdf).andExpect(status().isCreated()))
                .get("statementId").asLong();

        // Deleting the statement (3c) keeps the upload resolved; its statement link becomes null.
        jdbc.update("DELETE FROM txn WHERE statement_id = ?", statementId);
        jdbc.update("DELETE FROM statement WHERE id = ?", statementId);
        testParser.mode = Mode.OFF;
        long secondId = accepted(support.upload(checking, "card.pdf", pdf)).get("id").asLong();

        assertThat(secondId).isNotEqualTo(firstId);
        Map<String, Object> first = jdbc.queryForMap("SELECT * FROM unparsed_upload WHERE id = ?", firstId);
        assertThat(first.get("resolved_at")).isNotNull();
        assertThat(first.get("resolved_statement_id")).isNull();
        assertThat(openUploads(checking)).isEqualTo(1);
    }

    @Test
    void theSameFileOnTwoAccountsIsHeldTwiceIndependently() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long other = support.account(AccountType.CHECKING, "1003");
        byte[] pdf = ImportTestSupport.sample(UNRECOGNIZED);

        long onChecking = accepted(support.upload(checking, "card.pdf", pdf)).get("id").asLong();
        long onOther = accepted(support.upload(other, "card.pdf", pdf)).get("id").asLong();

        assertThat(onOther).isNotEqualTo(onChecking);
        assertThat(accepted(support.upload(other, "card.pdf", pdf)).get("id").asLong()).isEqualTo(onOther);
        assertThat(openUploads(checking)).isEqualTo(1);
        assertThat(openUploads(other)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ preview

    @Test
    void previewOfAnUnrecognizedPdfSaysSoAndWritesNothing() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        JsonNode preview = json(support.preview(checking, "card.pdf", ImportTestSupport.sample(UNRECOGNIZED))
                .andExpect(status().isOk()));
        JsonNode scan = json(support.preview(checking, "scan.pdf", ImportTestSupport.sample(NO_TEXT))
                .andExpect(status().isOk()));

        assertThat(preview.propertyNames()).containsExactlyInAnyOrder("format", "recognized", "reason");
        assertThat(preview.get("format").asString()).isEqualTo("pdf");
        assertThat(preview.get("recognized").asBoolean()).isFalse();
        assertThat(preview.get("reason").asString()).isEqualTo("UNRECOGNIZED_LAYOUT");
        assertThat(scan.get("reason").asString()).isEqualTo("NO_TEXT_LAYER");
        assertThat(support.count("SELECT count(*) FROM unparsed_upload WHERE account_id = ?", checking)).isZero();
    }

    @Test
    void previewOfARecognizedPdfIsTheStatementPreview() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        testParser.mode = Mode.CLAIM;

        JsonNode preview = json(support.preview(checking, "card.pdf", ImportTestSupport.sample(UNRECOGNIZED))
                .andExpect(status().isOk()));

        assertThat(preview.propertyNames()).containsExactlyInAnyOrder("format", "periodStart", "periodEnd",
                "transactionCount");
        assertThat(preview.get("format").asString()).isEqualTo(SwitchablePdfParser.ID);
        assertThat(preview.get("transactionCount").asInt()).isEqualTo(1);
        assertThat(support.count("SELECT count(*) FROM statement WHERE account_id = ?", checking)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private int openUploads(long accountId) {
        return support.count("SELECT count(*) FROM unparsed_upload WHERE account_id = ? AND resolved_at IS NULL",
                accountId);
    }

    private JsonNode accepted(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json(result.andExpect(status().isAccepted()));
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    enum Mode { OFF, CLAIM, CLAIM_FOR_ANOTHER_ACCOUNT, CLAIM_UNREADABLE }

    /**
     * A test-only PDF layout parser that claims every PDF while switched on and reads one fixed row
     * from it. Not bank-specific: it ignores the text entirely.
     */
    static final class SwitchablePdfParser implements PdfStatementParser {

        static final String ID = "test-pdf";

        volatile Mode mode = Mode.OFF;

        @Override
        public String id() {
            return ID;
        }

        @Override
        public FormatMatch detect(PdfDocumentText text) {
            return mode == Mode.OFF ? FormatMatch.NONE : FormatMatch.DEFINITE;
        }

        @Override
        public ParsedStatement parse(PdfDocumentText text, ParseContext context) {
            if (mode == Mode.CLAIM_UNREADABLE) {
                throw new StatementParseException(7, "unknown section heading");
            }
            String lastFour = mode == Mode.CLAIM_FOR_ANOTHER_ACCOUNT ? "9999" : "1001";
            LocalDate date = LocalDate.of(2026, 4, 10);
            ParsedTxn row = new ParsedTxn(date, date, date, "TEST ROW", "-10.00", new BigDecimal("-10.00"),
                    SourceSection.WITHDRAWAL, null, null, 1, "TEST ROW");
            return new ParsedStatement(new AccountHints(lastFour, AccountType.CHECKING, "USD"),
                    LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), new BigDecimal("100.00"),
                    new BigDecimal("90.00"), Map.of(), List.of(row), List.of());
        }
    }

    @TestConfiguration
    static class TestParserConfig {

        @Bean
        SwitchablePdfParser switchablePdfParser() {
            return new SwitchablePdfParser();
        }
    }
}
