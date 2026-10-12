package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.ingestion.csv.CsvStatementParser;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * SEC-10 and SEC-11 through the HTTP endpoints: content sniffing, XXE, file names and the size,
 * row and record limits at their boundaries. Every rejected file is sent to both the import and
 * the preview endpoint, and none may write anything.
 */
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class UploadHardeningTest {

    private static final String PROBLEM = "urn:finalysis:problem:";
    private static final String CHECKING_APRIL = "ofx/northfield-checking_2026-04.ofx";
    private static final String XXE_MARKER = "QZXXXESECRET";

    private final JdbcTemplate jdbc;
    private final ImportTestSupport support;

    UploadHardeningTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    // ------------------------------------------------------------------ sniffing

    static Stream<Arguments> sniffingMatrix() {
        byte[] csvText = "Date,Description,Amount\n2026-04-01,CAFE,-4.50\n".repeat(3).getBytes(StandardCharsets.US_ASCII);
        return Stream.of(
                Arguments.of("PDF named .pdf", "statement.pdf", ascii("%PDF-1.7\n1 0 obj"), "must end in"),
                Arguments.of("PDF named .csv", "statement.csv", ascii("%PDF-1.7\n1 0 obj"), "PDF statements"),
                Arguments.of("XLSX named .xlsx", "statement.xlsx", zip(), "must end in"),
                Arguments.of("XLSX named .csv", "statement.csv", zip(), "ZIP and spreadsheet"),
                Arguments.of("renamed .exe", "statement.csv", exe(), "not a text file"),
                Arguments.of("NUL byte", "statement.csv", concat(csvText, new byte[] {0}, csvText), "not a text file"),
                Arguments.of("UTF-16LE with BOM", "statement.csv", utf16(StandardCharsets.UTF_16LE, (byte) 0xFF, (byte) 0xFE), "not a text file"),
                Arguments.of("UTF-16BE with BOM", "statement.csv", utf16(StandardCharsets.UTF_16BE, (byte) 0xFE, (byte) 0xFF), "not a text file"),
                // 0x81 is invalid UTF-8 and undefined in windows-1252, the only fallback.
                Arguments.of("invalid UTF-8", "statement.csv", concat(csvText, new byte[] {(byte) 0x81}, csvText),
                        "not valid UTF-8 or windows-1252"),
                Arguments.of("HTML page named .csv", "statement.csv", html(), "web page"),
                Arguments.of("empty file", "statement.ofx", new byte[0], "empty"),
                Arguments.of("disallowed extension", "statement.xls", csvText, "must end in"),
                Arguments.of("double extension", "statement.csv.exe", csvText, "must end in"),
                Arguments.of("no extension", "statement", csvText, "must end in"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sniffingMatrix")
    void rejectedFilesAre415AndWriteNothing(String label, String fileName, byte[] bytes, String detail) throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        int[] before = rowCounts();

        for (ResultActions result : List.of(support.upload(checking, fileName, bytes), support.preview(checking, fileName, bytes))) {
            result.andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.type").value(PROBLEM + "unsupported-format"))
                    .andExpect(jsonPath("$.detail", containsString(detail)));
        }
        assertThat(rowCounts()).isEqualTo(before);
    }

    /** The HTML check would otherwise let this through: five lines with one comma each pass for a table. */
    @Test
    void htmlPageWouldOtherwiseBeDetectedAsCsv() {
        String withoutDoctype = new String(html(), StandardCharsets.US_ASCII).replaceFirst("(?s)^.*?<head>", "");
        assertThat(new CsvStatementParser().detect(ImportFile.of("x.csv",
                withoutDoctype.getBytes(StandardCharsets.US_ASCII)))).isEqualTo(FormatMatch.FALLBACK);
    }

    // ------------------------------------------------------------------ XXE

    static Stream<Arguments> xxeFiles() {
        return Stream.of(
                Arguments.of("external entity", "<!DOCTYPE OFX [<!ENTITY xxe SYSTEM \"file://%s\">]>", "&xxe;"),
                Arguments.of("parameter entity", "<!DOCTYPE OFX [<!ENTITY %% p SYSTEM \"file://%s\"> %%p;]>", "&xxe;"),
                Arguments.of("external DTD", "<!DOCTYPE OFX SYSTEM \"file://%s\">", "&xxe;"),
                Arguments.of("billion laughs", billionLaughs(), "&lol9;"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("xxeFiles")
    void xxeIsRejectedWithoutReadingTheEntity(String label, String doctype, String reference, @TempDir Path dir,
                                              CapturedOutput output) throws Exception {
        Path secret = Files.writeString(dir.resolve("secret.txt"), XXE_MARKER);
        Path dtd = Files.writeString(dir.resolve("secret.dtd"), "<!ENTITY xxe \"" + XXE_MARKER + "\">");
        String target = label.equals("external entity") ? secret.toString() : dtd.toString();
        String base = new String(ImportTestSupport.sample("edge-cases/ofx-xxe.ofx"), StandardCharsets.UTF_8);
        String file = base.replaceFirst("<!DOCTYPE[^\n]*", doctype.formatted(target).replace("$", "\\$"))
                .replace("&xxe;", reference);
        assertThat(file).contains(doctype.formatted(target)).doesNotContain(XXE_MARKER);
        long apex = support.account(AccountType.CREDIT_CARD, "2001");
        int[] before = rowCounts();

        for (String name : List.of("x.ofx", "x.qfx", "x.txt")) {
            byte[] bytes = file.getBytes(StandardCharsets.UTF_8);
            for (ResultActions result : List.of(support.upload(apex, name, bytes), support.preview(apex, name, bytes))) {
                String body = result.andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.type").value(PROBLEM + "parse-error"))
                        .andExpect(jsonPath("$.detail", containsString("DTD declarations")))
                        .andReturn().getResponse().getContentAsString();
                assertThat(body).doesNotContain(XXE_MARKER).doesNotContain("lol");
            }
        }
        assertThat(rowCounts()).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain(XXE_MARKER);
    }

    private static String billionLaughs() {
        StringBuilder dtd = new StringBuilder("<!DOCTYPE OFX [<!ENTITY lol \"lol\">");
        for (int i = 1; i <= 9; i++) {
            String previous = i == 1 ? "lol" : "lol" + (i - 1);
            dtd.append("<!ENTITY lol").append(i).append(" \"").append(("&" + previous + ";").repeat(10)).append("\">");
        }
        return dtd.append("]>").toString();
    }

    // ------------------------------------------------------------------ file names

    static Stream<Arguments> fileNames() {
        String longStem = "statement-" + "x".repeat(290);
        return Stream.of(
                Arguments.of("../../etc/passwd.ofx", "passwd.ofx"),
                Arguments.of("..\\..\\Windows\\april.ofx", "april.ofx"),
                Arguments.of("apr\u0000il\u001B[2J\r\n‮.ofx", "april[2J.ofx"),
                Arguments.of(longStem + ".ofx", longStem.substring(0, 251) + ".ofx"),
                Arguments.of("Jane Q Doe 123-45-6789 checking.ofx", "Jane Q Doe 123-45-6789 checking.ofx"));
    }

    /**
     * Names are stored without path or control characters and at most 255 characters long, but
     * not otherwise redacted: a person's name in a file name is the user's own data, kept for
     * display only. It is never logged and no response returns it.
     */
    @ParameterizedTest
    @MethodSource("fileNames")
    void fileNamesAreStoredCleanedAndNeverLogged(String original, String stored, CapturedOutput output) throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        String body = support.upload(checking, original, ImportTestSupport.sample(CHECKING_APRIL))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(jdbc.queryForObject("SELECT source_file_name FROM statement WHERE account_id = ?", String.class, checking))
                .isEqualTo(stored);
        assertThat(stored).hasSizeLessThanOrEqualTo(UploadCheck.MAX_FILE_NAME);
        assertThat(body).doesNotContain(stored);
        assertThat(output.getAll()).doesNotContain(stored).doesNotContain("Jane Q Doe").doesNotContain("passwd");
    }

    @Test
    void namesThatLoseTheirExtensionWhenCleanedAreRejected() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        for (String name : List.of("../../etc/passwd", "april.ofx/", "april.‮ofx.exe")) {
            support.upload(checking, name, ImportTestSupport.sample(CHECKING_APRIL))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.detail", containsString("must end in")));
        }
        assertThat(support.count("SELECT count(*) FROM statement WHERE account_id = ?", checking)).isZero();
    }

    // ------------------------------------------------------------------ limits

    /** The size limit through the real multipart path is in {@link MultipartDiskTest}. */
    @Test
    void fiftyThousandRowsAreReadAndOneMoreIsRefused() throws Exception {
        long card = support.account(AccountType.CREDIT_CARD, "2001");
        String header = "Date,Description,Amount\n";
        String rows = "2026-04-01,CAFE,-1.00\n".repeat(50_000);
        saveSimpleMapping(card);

        support.preview(card, "rows.csv", ascii(header + rows)).andExpect(status().isOk());
        byte[] tooMany = ascii(header + rows + "2026-04-01,CAFE,-1.00\n");
        for (ResultActions result : List.of(support.preview(card, "rows.csv", tooMany), support.upload(card, "rows.csv", tooMany))) {
            result.andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type").value(PROBLEM + "parse-error"))
                    .andExpect(jsonPath("$.detail").value("line 50002: the file has more than 50000 rows"));
        }
    }

    @Test
    void recordsOfSixtyFourKilobytesAreReadAndOneCharacterMoreIsRefused() throws Exception {
        long card = support.account(AccountType.CREDIT_CARD, "2001");
        String header = "Date,Description,Amount\n";
        String atLimit = "2026-04-01,\"" + "A".repeat(64 * 1024 - 19) + "\",-1.00";
        assertThat(atLimit).hasSize(64 * 1024);
        String overLimit = atLimit.replace("\"A", "\"AA");
        saveSimpleMapping(card);

        support.preview(card, "wide.csv", ascii(header + atLimit + "\n" + atLimit + "\n")).andExpect(status().isOk());
        byte[] tooWide = ascii(header + overLimit + "\n" + overLimit + "\n");
        for (ResultActions result : List.of(support.preview(card, "wide.csv", tooWide), support.upload(card, "wide.csv", tooWide))) {
            result.andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail").value(containsString("record is longer than 65536 characters")));
        }
        assertThat(support.count("SELECT count(*) FROM statement WHERE account_id = ?", card)).isZero();
    }

    // ------------------------------------------------------------------ SEC-11

    @Test
    void formulaLeadingDescriptionsAreStoredUnchanged() throws Exception {
        long card = support.account(AccountType.CREDIT_CARD, "2001");
        saveSimpleMapping(card);
        List<String> descriptions = List.of("=SUM(A1:A9)", "+1 PREMIUM CAFE", "-CANCELLED ORDER", "@HANDLE COFFEE",
                "\tTAB CAFE", "\rCR CAFE", "=HYPERLINK(\"http://example.invalid\",\"x\")");
        StringBuilder csv = new StringBuilder("Date,Description,Amount\n");
        for (int i = 0; i < descriptions.size(); i++) {
            csv.append("2026-04-0").append(i + 1).append(",\"").append(descriptions.get(i).replace("\"", "\"\""))
                    .append("\",-1.00\n");
        }

        support.upload(card, "formulas.csv", ascii(csv.toString())).andExpect(status().isCreated());

        assertThat(jdbc.queryForList("SELECT raw_description FROM txn WHERE account_id = ? ORDER BY txn_date",
                String.class, card)).containsExactlyElementsOf(descriptions);
    }

    // ------------------------------------------------------------------ helpers

    /** Date, Description, Amount with ISO dates and signed amounts. */
    private void saveSimpleMapping(long accountId) throws Exception {
        support.putMapping(accountId, """
                {"headers": ["Date", "Description", "Amount"], "dateColumn": "Date", "dateFormat": "yyyy-MM-dd",
                 "descriptionColumns": ["Description"], "amountMode": "SINGLE", "amountColumn": "Amount", "sign": "AS_IS"}
                """).andExpect(status().isCreated());
    }

    /** Statements, transactions and CSV mappings in the whole database. */
    private int[] rowCounts() {
        return new int[] {
                support.count("SELECT count(*) FROM statement"),
                support.count("SELECT count(*) FROM txn"),
                support.count("SELECT count(*) FROM csv_mapping")};
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** The start of an XLSX file: a ZIP local file header for {@code [Content_Types].xml}. */
    private static byte[] zip() {
        return concat(new byte[] {'P', 'K', 3, 4, 20, 0, 6, 0, 8, 0}, ascii("[Content_Types].xml"));
    }

    /** A Windows executable header renamed to .csv: "MZ", then binary fields with NUL bytes. */
    private static byte[] exe() {
        return concat(new byte[] {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0}, ascii("This program cannot be run in DOS mode."));
    }

    private static byte[] utf16(Charset charset, byte bom1, byte bom2) {
        return concat(new byte[] {bom1, bom2}, "Date,Description,Amount\n2026-04-01,CAFE,-4.50\n".getBytes(charset));
    }

    /** A bank's "session expired" page saved as .csv; its styles and script have one comma per line. */
    private static byte[] html() {
        return ascii("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <style>body { font-family: Arial, sans-serif; }</style>
                <style>h1 { margin: 0 auto; padding: 0, 0; }</style>
                <script>var retry = [1, 2];</script>
                <script>var next = [3, 4];</script>
                <title>Session expired, please sign in</title>
                </head>
                <body><p>Your session expired, sign in again.</p></body>
                </html>
                """);
    }
}
