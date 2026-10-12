package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * SEC-10 across the whole API: every endpoint, on success and on every failure it can return,
 * with marker strings planted in descriptions, file names, CSV header names, other cells and
 * account numbers. With the app's own packages at DEBUG, no marker may reach the log, and no
 * problem response may carry one.
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

    // ------------------------------------------------------------------ helpers

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
