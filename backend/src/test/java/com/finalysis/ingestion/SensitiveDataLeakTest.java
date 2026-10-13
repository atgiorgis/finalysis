package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.ingestion.csv.CsvMappingService;
import com.finalysis.ingestion.csv.CsvMappingSpec;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * SEC-10 across the whole API: every endpoint, on success and on every failure it can return,
 * with marker strings planted in descriptions, file names, CSV header names, other cells and
 * account numbers. With the app's own packages at DEBUG, no marker may reach the log, and no
 * problem response may carry one.
 *
 * <p>PDFs are covered by {@link #noPdfMarkerReachesTheLogOrAResponse} (Phase 3b step 8), on every
 * PDF path reachable today.
 *
 * <p>One documented exception: a {@code mapping-mismatch} response's {@code preview} carries the
 * file's header names (and a suggested mapping built from them) so the user can fix the mapping;
 * SEC-10 allows column names. Its {@code detail} and everything else still may not.
 */
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "logging.level.com.finalysis=DEBUG")
class SensitiveDataLeakTest {

    private static final String PROBLEM = "urn:finalysis:problem:";
    private static final String DESCRIPTION = "QZXDESC";
    private static final String FILE_NAME = "QZXFILE";
    private static final String HEADER = "QZXHDR";
    private static final String CELL = "QZXCELL";
    /** The first eight digits of every full account number below; only last fours may leave a parser. */
    private static final String ACCOUNT_NUMBER = "73005512";
    private static final List<String> MARKERS = List.of(DESCRIPTION, FILE_NAME, HEADER, CELL, ACCOUNT_NUMBER);
    /** Makes {@link AmbiguityProbe} claim a file along with the OFX parser. */
    private static final String AMBIGUOUS = "AMBIGUOUS-PROBE";

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final ImportTestSupport support;
    private final List<String> problems = new ArrayList<>();

    @MockitoSpyBean
    private CsvMappingService csvMappings;

    SensitiveDataLeakTest(MockMvc mockMvc, JsonMapper jsonMapper, AccountRepository accounts, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    /** A second parser that claims marked OFX files, so the registry reports an ambiguous format. */
    @TestConfiguration(proxyBeanMethods = false)
    static class AmbiguityProbe {

        @Bean
        StatementParser ambiguityProbe() {
            return new StatementParser() {
                @Override
                public String id() {
                    return "probe";
                }

                @Override
                public FormatMatch detect(ImportFile file) {
                    return new String(file.bytes(), StandardCharsets.ISO_8859_1).contains(AMBIGUOUS)
                            ? FormatMatch.DEFINITE : FormatMatch.NONE;
                }

                @Override
                public ParsedStatement parse(ImportFile file, ParseContext context) {
                    throw new UnsupportedOperationException("never selected");
                }
            };
        }
    }

    @Test
    void noMarkerReachesTheLogOrAProblemResponse(CapturedOutput output) throws Exception {
        assertThat(LoggerFactory.getLogger(StatementImportService.class).isDebugEnabled()).isTrue();
        assertThat(LoggerFactory.getLogger("org.hibernate.orm.jdbc.bind").isTraceEnabled()).isFalse();

        long checking = exerciseOfxEndpoints();
        long card = exerciseCsvEndpoints();
        exerciseStatementEndpoints(checking, card);
        exerciseTransferAndReviewEndpoints(checking, card);
        databaseErrorsCarryNoRowValues();

        assertThat(problems).hasSizeGreaterThanOrEqualTo(20);
        for (String problem : problems) {
            assertNoMarkers(problem);
        }
        assertThat(output.getAll())
                .contains("Imported statement", "Previewed a ofx file", "Previewed a csv file",
                        "Statement parsers [ofx, probe] all claim the same file");
        assertNoMarkers(output.getAll());
    }

    /**
     * Every PDF path reachable today, with markers in the page text (customer line, account number,
     * row descriptions, summary lines), the file name, the metadata, a form field, a JavaScript
     * action, an embedded file and a link: a recognized layout (422, parsing not implemented while
     * Phase 3b is paused), an unrecognized layout and a re-upload (202), no text layer (202),
     * password required, too many pages, unreadable and truncated files, extension and kind
     * disagreeing, and an unknown account. The 202 body may carry the file name (the user's own
     * data, stored for display); nothing else in it may carry a marker.
     */
    @Test
    void noPdfMarkerReachesTheLogOrAResponse(CapturedOutput output) throws Exception {
        // PDFBox keeps the first font mapper it is asked for; the app's must be in place before the
        // fonts below are created, or building them would scan system fonts and write a cache.
        assertThat(FontMappers.instance().getClass().getSimpleName()).isEqualTo("NoSystemFontsMapper");
        long checking = support.account(AccountType.CHECKING, "1001");
        String name = "../" + FILE_NAME + " Jane Q Doe statement.pdf";
        byte[] recognized = recognizedLayout();
        byte[] unrecognized = unrecognizedLayout();

        for (ResultActions result : List.of(support.upload(checking, name, recognized),
                support.preview(checking, name, recognized))) {
            problem(result, 422, "parse-error");
            result.andExpect(jsonPath("$.detail").value(containsString("recognized (boa-checking)")));
        }
        accepted(support.upload(checking, name, unrecognized), "UNRECOGNIZED_LAYOUT");
        accepted(support.upload(checking, name, unrecognized), "UNRECOGNIZED_LAYOUT");
        support.preview(checking, name, unrecognized).andExpect(status().isOk())
                .andExpect(jsonPath("$.recognized").value(false))
                .andDo(result -> accepted(result.getResponse().getContentAsString()));
        accepted(support.upload(checking, name, noTextLayer()), "NO_TEXT_LAYER");

        for (ResultActions result : List.of(support.upload(checking, name, passwordRequired()),
                support.preview(checking, name, passwordRequired()))) {
            problem(result, 422, "pdf-encrypted");
        }
        problem(support.upload(checking, name, pages(51)), 422, "pdf-too-complex");
        problem(support.preview(checking, name, pages(51)), 422, "pdf-too-complex");
        byte[] unreadable = ("%PDF-1.7\n" + DESCRIPTION + " " + ACCOUNT_NUMBER + "1001 not a PDF body\n")
                .getBytes(StandardCharsets.US_ASCII);
        problem(support.upload(checking, name, unreadable), 422, "parse-error");
        problem(support.preview(checking, name, unreadable), 422, "parse-error");
        // PDFBox recovers what it can from a truncated file: it is read, or refused as unreadable.
        ResultActions truncated = support.upload(checking, name, Arrays.copyOf(recognized, recognized.length / 2));
        int truncatedStatus = truncated.andReturn().getResponse().getStatus();
        if (truncatedStatus == 202) {
            accepted(truncated.andReturn().getResponse().getContentAsString());
        } else {
            problem(truncated, 422, null);
        }

        problem(support.upload(checking, FILE_NAME + " statement.csv", unrecognized), 415, "unsupported-format");
        problem(support.upload(checking, FILE_NAME + " statement.pdf",
                (DESCRIPTION + "," + ACCOUNT_NUMBER + "\n").getBytes(StandardCharsets.US_ASCII)), 415, "unsupported-format");
        problem(support.upload(999_999_999, name, unrecognized), 404, null);
        problem(support.preview(999_999_999, name, unrecognized), 404, null);

        assertThat(support.count("SELECT count(*) FROM statement WHERE account_id = ?", checking)).isZero();
        for (String problem : problems) {
            assertNoMarkers(problem);
        }
        assertThat(output.getAll()).contains("No PDF layout recognized", "Previewed a pdf file");
        assertNoMarkers(output.getAll());
    }

    // ------------------------------------------------------------------ OFX

    private long exerciseOfxEndpoints() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long otherChecking = support.account(AccountType.CHECKING, "9999");
        byte[] marked = ofx("USD", "-25.00", "");
        String name = "../" + FILE_NAME + " Jane Q Doe checking.ofx";

        support.preview(checking, name, marked).andExpect(status().isOk());
        support.upload(checking, name, marked).andExpect(status().isCreated());
        problem(support.upload(checking, name, marked), 409, "already-imported");
        problem(support.upload(checking, name, ofx("USD", CELL, "")), 422, "parse-error");
        problem(support.preview(checking, name, ofx("USD", CELL, "")), 422, "parse-error");
        problem(support.upload(otherChecking, name, ofx("USD", "-1.00", "")), 422, "account-mismatch");
        problem(support.preview(otherChecking, name, ofx("USD", "-1.00", "")), 422, "account-mismatch");
        problem(support.upload(checking, name, ofx("EUR", "-1.00", "")), 422, "unsupported-currency");
        problem(support.upload(checking, name, ofx("USD", "-1.00", AMBIGUOUS)), 422, "ambiguous-format");
        problem(support.upload(checking, FILE_NAME + ".ofx.exe", marked), 415, "unsupported-format");
        problem(support.upload(checking, FILE_NAME + ".ofx",
                (DESCRIPTION + " " + ACCOUNT_NUMBER + "\n").getBytes(StandardCharsets.US_ASCII)), 415, "unsupported-format");
        byte[] tooLarge = new byte[(int) UploadCheck.MAX_BYTES + 1];
        byte[] markerBytes = (DESCRIPTION + ACCOUNT_NUMBER).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(markerBytes, 0, tooLarge, 0, markerBytes.length);
        problem(support.upload(checking, name, tooLarge), 413, "payload-too-large");
        problem(support.upload(checking, name, ofx("USD", "-2.00", ""),
                Map.of("openingBalance", CELL, "closingBalance", ACCOUNT_NUMBER + ".001")), 400, null);
        problem(support.upload(999_999_999, name, marked), 404, null);
        problem(support.preview(999_999_999, name, marked), 404, null);
        return checking;
    }

    /**
     * A one-statement OFX file for account ...1001 whose full number starts with the account
     * marker. One row is a transfer to another account (its number is masked in the stored source
     * line); {@code extra} is added to a description.
     */
    private static byte[] ofx(String currency, String amount, String extra) {
        return ("""
                OFXHEADER:100
                DATA:OFXSGML
                VERSION:102
                SECURITY:NONE
                ENCODING:USASCII
                CHARSET:1252
                COMPRESSION:NONE
                OLDFILEUID:NONE
                NEWFILEUID:NONE

                <OFX>
                <SIGNONMSGSRSV1>
                <SONRS>
                <STATUS>
                <CODE>0
                <SEVERITY>INFO
                </STATUS>
                <DTSERVER>20260501080000
                </SONRS>
                </SIGNONMSGSRSV1>
                <BANKMSGSRSV1>
                <STMTTRNRS>
                <TRNUID>1
                <STMTRS>
                <CURDEF>%1$s
                <BANKACCTFROM>
                <BANKID>999000111
                <ACCTID>%2$s1001
                <ACCTTYPE>CHECKING
                </BANKACCTFROM>
                <BANKTRANLIST>
                <DTSTART>20260401
                <DTEND>20260430
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260405
                <TRNAMT>%3$s
                <FITID>LEAK1
                <NAME>%4$s GROCER %5$s
                <MEMO>%4$s card %2$s1001
                </STMTTRN>
                <STMTTRN>
                <TRNTYPE>XFER
                <DTPOSTED>20260410
                <TRNAMT>-100.00
                <FITID>LEAK2
                <NAME>%4$s TRANSFER TO SAVINGS
                <BANKACCTTO>
                <BANKID>999000111
                <ACCTID>%2$s9999
                <ACCTTYPE>SAVINGS
                </BANKACCTTO>
                </STMTTRN>
                </BANKTRANLIST>
                <LEDGERBAL>
                <BALAMT>500.00
                <DTASOF>20260430
                </LEDGERBAL>
                </STMTRS>
                </STMTTRNRS>
                </BANKMSGSRSV1>
                </OFX>
                """).formatted(currency, ACCOUNT_NUMBER, amount, DESCRIPTION, extra).getBytes(StandardCharsets.US_ASCII);
    }

    // ------------------------------------------------------------------ CSV and its mapping

    private long exerciseCsvEndpoints() throws Exception {
        long card = support.account(AccountType.CREDIT_CARD, "2001");
        String name = FILE_NAME + " card export.csv";
        String header = HEADER + " Date," + HEADER + " Merchant," + HEADER + " Amount," + HEADER + " Account\n";
        byte[] april = csv(header + row("2026-04-02", "COFFEE", "-4.50") + row("2026-04-09", "BOOKS", "-12.00"));

        problem(support.upload(card, name, april), 422, "mapping-required");
        support.preview(card, name, april).andExpect(status().isOk()).andExpect(jsonPath("$.mappingStatus").value("NONE"));
        problem(mockMvc.perform(get("/api/accounts/{id}/csv-mapping", card)), 404, null);

        String mapping = """
                {"headers": ["%1$s Date", "%1$s Merchant", "%1$s Amount", "%1$s Account"],
                 "dateColumn": "%1$s Date", "dateFormat": "yyyy-MM-dd", "descriptionColumns": ["%1$s Merchant"],
                 "amountMode": "SINGLE", "amountColumn": "%1$s Amount", "sign": "AS_IS"}
                """.formatted(HEADER);
        problem(support.putMapping(card, mapping.replace("yyyy-MM-dd", CELL)), 400, null);
        problem(support.putMapping(card, mapping.replace("\"amountColumn\": \"" + HEADER, "\"amountColumn\": \"" + CELL)), 400, null);
        problem(support.putMapping(card, mapping.replace("\"amountMode\": \"SINGLE\",", "\"amountMode\": \"SINGLE\", \"note\": \"" + CELL + "\",")), 400, null);
        problem(support.putMapping(card, "{\"headers\": [\"" + HEADER + "\"" + CELL), 400, null);
        support.putMapping(card, mapping).andExpect(status().isCreated());
        mockMvc.perform(get("/api/accounts/{id}/csv-mapping", card)).andExpect(status().isOk());

        support.preview(card, name, april).andExpect(status().isOk()).andExpect(jsonPath("$.mappingStatus").value("MATCHES"));
        support.upload(card, name, april).andExpect(status().isCreated());
        problem(support.upload(card, name, april), 409, "already-imported");
        problem(support.upload(card, name, csv(header + row(CELL, "TEA", "-3.00"))), 422, "parse-error");
        problem(support.upload(card, name, csv(header + row("2026-04-03", "TEA", CELL))), 422, "parse-error");
        // Detection needs a consistent table first; the short row comes after it.
        problem(support.upload(card, name, csv(header + row("2026-04-03", "TEA", "-3.00").repeat(4)
                + "2026-04-04," + DESCRIPTION + " TEA\n")), 422, "parse-error");
        problem(support.upload(card, name, csv(HEADER + " Day," + HEADER + " Payee," + HEADER + " Total\n"
                + "2026-04-03," + DESCRIPTION + " TEA,-3.00\n")), 422, "mapping-mismatch");
        problem(support.upload(card, name, csv(header + row("2026-04-03", "TEA", "-3.00")),
                Map.of("periodStart", "2026-05-01", "periodEnd", "2026-04-01")), 400, null);

        // A locked field (the sign) can't change without confirmation once the mapping is used.
        String inverted = mapping.replace("AS_IS", "INVERTED");
        problem(support.putMapping(card, inverted), 409, "mapping-locked");
        mockMvc.perform(put("/api/accounts/{id}/csv-mapping", card).param("confirmFingerprintChange", "true")
                .contentType(MediaType.APPLICATION_JSON).content(inverted)).andExpect(status().isOk());

        // The mapping changes between the import's snapshot and the writer's check.
        doAnswer(invocation -> {
            CsvMappingSpec snapshot = (CsvMappingSpec) invocation.callRealMethod();
            return new CsvMappingSpec(snapshot.values(), snapshot.updatedAt().minusSeconds(1));
        }).when(csvMappings).snapshot(anyLong());
        problem(support.upload(card, name, csv(header + row("2026-04-20", "LAMP", "-30.00"))), 409, "mapping-changed");
        reset(csvMappings);
        return card;
    }

    private static String row(String date, String description, String amount) {
        return date + "," + DESCRIPTION + " " + description + "," + amount + "," + ACCOUNT_NUMBER + "2001\n";
    }

    private static byte[] csv(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ statements

    private void exerciseStatementEndpoints(long... accounts) throws Exception {
        for (long account : accounts) {
            mockMvc.perform(get("/api/accounts/{id}/statements", account)).andExpect(status().isOk());
            mockMvc.perform(get("/api/accounts/{id}/statements", account).param("status", "NEEDS_REVIEW"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/statements/{id}", support.firstStatementId(account))).andExpect(status().isOk());
        }
        problem(mockMvc.perform(get("/api/statements/{id}", 999_999_999)), 404, null);
        problem(mockMvc.perform(get("/api/accounts/{id}/statements", 999_999_999)), 404, null);
    }

    // ------------------------------------------------------------------ transfers and the review queue (3c)

    /**
     * Every error of the transfer actions (step 4), the review actions (step 5), the queue and join and
     * dismiss (step 6), on txns whose descriptions carry markers. Their problems also carry no amount.
     */
    private void exerciseTransferAndReviewEndpoints(long checking, long card) throws Exception {
        long out = markedTxn(checking, "-417.23", "WITHDRAWAL");
        long in = markedTxn(card, "388.61", "PAYMENT"); // not equal and opposite: linking them is a 422
        List<String> amounts = jdbc.queryForList("SELECT abs(amount)::text FROM txn WHERE id IN (?, ?)", String.class,
                out, in);
        int before = problems.size();

        problem(transfer("{\"outTxnId\": " + out + ", \"inTxnId\": " + in + "}"), 422, "transfer-invalid");
        problem(transfer("{}"), 422, "transfer-invalid");
        problem(transfer("{\"outTxnId\": " + in + "}"), 422, "transfer-invalid");
        problem(transfer("{\"outTxnId\": 999999999}"), 404, null);
        long outLink = json(transfer("{\"outTxnId\": " + out + "}").andExpect(status().isCreated())).get("id").asLong();
        long inLink = json(transfer("{\"inTxnId\": " + in + "}").andExpect(status().isCreated())).get("id").asLong();
        problem(transfer("{\"outTxnId\": " + out + "}"), 409, "transfer-conflict");
        for (String path : List.of("/api/transfers/join", "/api/transfers/join/dismiss")) {
            problem(post(path, "{\"outLinkId\": " + outLink + ", \"inLinkId\": " + inLink + "}"), 422,
                    "transfer-invalid");
            problem(post(path, "{\"outLinkId\": " + inLink + ", \"inLinkId\": " + outLink + "}"), 409,
                    "transfer-conflict");
            problem(post(path, "{\"outLinkId\": 999999999, \"inLinkId\": " + inLink + "}"), 404, null);
            problem(post(path, "{\"outLinkId\": " + outLink + "}"), 400, null);
        }
        problem(mockMvc.perform(get("/api/transfers/{id}", 999_999_999)), 404, null);
        problem(mockMvc.perform(MockMvcRequestBuilders.post("/api/transfers/{id}/confirm", 999_999_999)), 404, null);
        problem(mockMvc.perform(MockMvcRequestBuilders.delete("/api/transfers/{id}", 999_999_999)), 404, null);
        problem(mockMvc.perform(MockMvcRequestBuilders.post("/api/transactions/{id}/not-transfer", 999_999_999)), 404,
                null);
        problem(mockMvc.perform(get("/api/transfers").param("status", "PENDING")), 400, null);
        mockMvc.perform(MockMvcRequestBuilders.post("/api/transfers/detect")).andExpect(status().isOk());
        mockMvc.perform(get("/api/transfers").param("accountId", String.valueOf(checking))).andExpect(status().isOk());

        mockMvc.perform(get("/api/review-queue")).andExpect(status().isOk());
        mockMvc.perform(get("/api/review-queue").param("accountId", String.valueOf(checking))).andExpect(status().isOk());
        problem(mockMvc.perform(get("/api/review-queue").param("kind", "EVERYTHING")), 400, null);
        long statement = jdbc.queryForObject("SELECT statement_id FROM txn WHERE id = ?", Long.class, out);
        if (!"NEEDS_REVIEW".equals(jdbc.queryForObject("SELECT status FROM statement WHERE id = ?", String.class,
                statement))) {
            problem(mockMvc.perform(MockMvcRequestBuilders.post("/api/statements/{id}/review", statement)), 409,
                    "not-reviewable");
        }
        problem(mockMvc.perform(MockMvcRequestBuilders.post("/api/statements/{id}/review", 999_999_999)), 404, null);
        problem(mockMvc.perform(MockMvcRequestBuilders.delete("/api/statements/{id}", 999_999_999)), 404, null);
        problem(mockMvc.perform(get("/api/unparsed-uploads/{id}", 999_999_999)), 404, null);
        problem(mockMvc.perform(MockMvcRequestBuilders.post("/api/unparsed-uploads/{id}/dismiss", 999_999_999)), 404,
                null);

        assertThat(problems.size() - before).isGreaterThanOrEqualTo(20);
        for (String problem : problems.subList(before, problems.size())) {
            assertThat(problem).as("SEC-10: no amount in a problem").doesNotContain(amounts);
        }
    }

    /** A txn in a statement of its own, with a marker in its description. */
    private long markedTxn(long accountId, String amount, String section) {
        long statement = jdbc.queryForObject("""
                INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash, parser)
                VALUES (?, DATE '2026-04-01', DATE '2026-04-30', ?, ?, 'fixture') RETURNING id
                """, Long.class, accountId, FILE_NAME + ".csv", "hash-" + UUID.randomUUID());
        return jdbc.queryForObject("""
                INSERT INTO txn (account_id, statement_id, txn_date, raw_description, amount, source_section, fingerprint)
                VALUES (?, ?, DATE '2026-04-10', ?, ?::numeric, ?, ?) RETURNING id
                """, Long.class, accountId, statement, DESCRIPTION + " TRANSFER " + ACCOUNT_NUMBER + "9", amount, section,
                "fp-" + UUID.randomUUID());
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private ResultActions transfer(String body) throws Exception {
        return post("/api/transfers", body);
    }

    private ResultActions post(String path, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ------------------------------------------------------------------ database errors

    /**
     * Postgres error details quote the failing row; with {@code logServerErrorDetail=false} the
     * exception (and so any logged stack trace) names the constraint but carries no values.
     */
    private void databaseErrorsCarryNoRowValues() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO account (name, institution, type, last_four) VALUES (?, ?, 'CHECKING', ?)",
                DESCRIPTION + " Jane Q Doe", ACCOUNT_NUMBER, "12x4"))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, e -> {
                    StringWriter trace = new StringWriter();
                    e.printStackTrace(new PrintWriter(trace));
                    assertThat(trace.toString()).contains("ck_account_last_four");
                    assertNoMarkers(trace.toString());
                });
    }

    // ------------------------------------------------------------------ PDFs (built in memory, never committed)

    /** Page 1 of the BoA checking layout, enough for detection, with markers everywhere a statement has text. */
    private static byte[] recognizedLayout() throws IOException {
        return pdf(1, List.of(
                "SYNTHETIC TEST DATA",
                DESCRIPTION + " JANE Q DOE",
                "for April 1, 2026 to April 30, 2026",
                "Account number: " + ACCOUNT_NUMBER + " 1001",
                "Account summary",
                "Beginning balance on April 1, 2026 $100.00",
                "Deposits and other additions 0.00",
                "Withdrawals and other subtractions -25.00",
                "Ending balance on April 30, 2026 $75.00",
                "Withdrawals and other subtractions",
                "Date Description Amount",
                "04/05/26 " + DESCRIPTION + " GROCER CARD " + ACCOUNT_NUMBER + " -25.00",
                CELL + " " + ACCOUNT_NUMBER,
                "Total withdrawals and other subtractions -$25.00"));
    }

    /** A text PDF in no known layout. */
    private static byte[] unrecognizedLayout() throws IOException {
        return pdf(1, List.of("SYNTHETIC TEST DATA", DESCRIPTION + " JANE Q DOE", "Card " + ACCOUNT_NUMBER + "2001",
                "04/05/2026 " + DESCRIPTION + " GROCER " + CELL + " 25.00"));
    }

    /** No page text at all: the markers are only in metadata, a form field and the other inert parts. */
    private static byte[] noTextLayer() throws IOException {
        return pdf(1, List.of());
    }

    private static byte[] pages(int count) throws IOException {
        return pdf(count, List.of(DESCRIPTION + " " + ACCOUNT_NUMBER + "1001 -25.00"));
    }

    private static byte[] passwordRequired() throws IOException {
        try (PDDocument document = document(1, List.of(DESCRIPTION + " " + ACCOUNT_NUMBER + "1001"))) {
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-" + CELL, "user-" + CELL,
                    new AccessPermission());
            policy.setEncryptionKeyLength(256);
            document.protect(policy);
            return save(document);
        }
    }

    private static byte[] pdf(int pageCount, List<String> lines) throws IOException {
        try (PDDocument document = document(pageCount, lines)) {
            return save(document);
        }
    }

    /**
     * {@code pageCount} Letter pages, each with {@code lines} drawn top to bottom 16 pt apart, plus
     * markers in the parts the extractor never reads: metadata, a JavaScript open action, an
     * embedded file, a form field value and a link annotation.
     */
    private static PDDocument document(int pageCount, List<String> lines) throws IOException {
        PDDocument document = new PDDocument();
        PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        for (int p = 0; p < pageCount; p++) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float y = PDRectangle.LETTER.getHeight() - 60;
                for (String line : lines) {
                    content.beginText();
                    content.setFont(font, 10);
                    content.newLineAtOffset(72, y);
                    content.showText(line);
                    content.endText();
                    y -= 16;
                }
            }
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(72, 40, 100, 20));
            PDActionURI uri = new PDActionURI();
            uri.setURI("https://example.invalid/" + CELL);
            link.setAction(uri);
            link.setContents(DESCRIPTION);
            page.getAnnotations().add(link);
        }
        PDDocumentInformation info = new PDDocumentInformation();
        info.setTitle(DESCRIPTION + " statement " + ACCOUNT_NUMBER);
        info.setAuthor(CELL);
        info.setSubject(FILE_NAME);
        document.setDocumentInformation(info);
        document.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('" + DESCRIPTION + "');"));
        PDEmbeddedFile embedded = new PDEmbeddedFile(document,
                new ByteArrayInputStream((CELL + " " + ACCOUNT_NUMBER).getBytes(StandardCharsets.US_ASCII)));
        PDComplexFileSpecification spec = new PDComplexFileSpecification();
        spec.setFile(FILE_NAME + ".txt");
        spec.setEmbeddedFile(embedded);
        PDEmbeddedFilesNameTreeNode files = new PDEmbeddedFilesNameTreeNode();
        files.setNames(Map.of(FILE_NAME, spec));
        PDDocumentNameDictionary names = new PDDocumentNameDictionary(document.getDocumentCatalog());
        names.setEmbeddedFiles(files);
        document.getDocumentCatalog().setNames(names);
        PDAcroForm form = new PDAcroForm(document);
        document.getDocumentCatalog().setAcroForm(form);
        PDTextField field = new PDTextField(form);
        field.setPartialName("account");
        form.getFields().add(field);
        field.getCOSObject().setString(COSName.V, ACCOUNT_NUMBER + "1001 " + DESCRIPTION);
        return document;
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        document.save(bytes);
        return bytes.toByteArray();
    }

    // ------------------------------------------------------------------ helpers

    /** Checks a 202 unparsed-upload response and keeps its body, without the file name, for the marker check. */
    private void accepted(ResultActions result, String reason) throws Exception {
        result.andExpect(status().isAccepted()).andExpect(jsonPath("$.reason").value(reason));
        accepted(result.andReturn().getResponse().getContentAsString());
    }

    private void accepted(String body) {
        JsonNode json = jsonMapper.readTree(body);
        if (json.has("fileName")) {
            // The cleaned name is shown back to the user: the path is gone, the rest is their own data.
            assertThat(((ObjectNode) json).remove("fileName").asString()).doesNotContain("..", "/");
        }
        problems.add(json.toString());
    }

    /** Checks a problem response's status and type, and keeps its body for the marker check. */
    private void problem(ResultActions result, int status, String type) throws Exception {
        result.andExpect(status().is(status));
        if (type != null) {
            result.andExpect(jsonPath("$.type").value(PROBLEM + type));
        }
        JsonNode body = jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
        if ("mapping-mismatch".equals(type)) {
            // The documented exception: header names in the preview, never rows or descriptions.
            JsonNode preview = ((ObjectNode) body).remove("preview");
            assertThat(preview.get("headers").get(1).asString()).isEqualTo(HEADER + " Payee");
            assertThat(preview.has("rows")).isFalse();
            assertThat(preview.toString()).doesNotContain(DESCRIPTION, CELL, ACCOUNT_NUMBER, FILE_NAME);
        }
        problems.add(body.toString());
    }

    private static void assertNoMarkers(String text) {
        for (String marker : MARKERS) {
            assertThat(text).as("marker %s", marker).doesNotContain(marker);
        }
    }
}
