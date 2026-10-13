package com.finalysis.ingestion.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.ingestion.ImportFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks the committed synthetic PDFs in {@code sample-data/pdf/} (produced by
 * {@code tools/SyntheticPdfGenerator.java}, ING-11): byte-stable regeneration, extraction equal to
 * the generator's own line model (§1.3 golden texts), marking, and that the expectations the
 * parser tests will rely on are consistent with the answer key and with what is printed. The
 * readers here are deliberately minimal; real parsing belongs to the layout parser.
 */
class SyntheticPdfTest {

    private static final Path SAMPLE_DATA = Path.of("..", "sample-data");
    private static final Path PDF = SAMPLE_DATA.resolve("pdf");
    private static final String MARK = "SYNTHETIC TEST DATA";
    private static final DateTimeFormatter ROW_DATE = DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);
    private static final float DESCRIPTION_X = 90;
    private static final Pattern ROW_DATE_WORD = Pattern.compile("\\d\\d/\\d\\d/\\d\\d");
    private static final Pattern AMOUNT_WORD = Pattern.compile("-?\\$?[\\d,]+\\.\\d\\d");
    private static final Pattern REAL_BANK_NAMES = Pattern.compile("(?i)\\b(bank of america|bofa|b of a|chase|"
            + "jpmorgan|wells fargo|citibank|citi|citigroup|capital one|u\\.?s\\.? bank|pnc|truist|td bank|hsbc|"
            + "barclays|santander|ally bank|schwab|fifth third|regions bank|citizens bank|keybank|huntington|"
            + "navy federal|usaa|synchrony|american express|amex|discover bank|goldman sachs|morgan stanley)\\b");

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    record ManifestEntry(String file, String parser, String unparsedReason, String answerKeyFile, String periodStart,
                         String periodEnd, String openingBalance, String closingBalance, String status) {
        boolean recognized() {
            return parser.equals("boa-checking");
        }

        boolean edgeCase() {
            return file.startsWith("edge-cases/");
        }
    }

    record AnswerKeyRow(LocalDate date, String description, BigDecimal amount) {}

    static Stream<String> pdfFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(PDF)) {
            return walk.filter(path -> path.toString().endsWith(".pdf")).map(path -> PDF.relativize(path).toString())
                    .sorted().toList().stream();
        }
    }

    // ------------------------------------------------------------------ the files themselves

    /** Runs the generator into a temporary directory; its output must equal the committed files byte for byte. */
    @Test
    void generatorOutputMatchesCommittedFiles(@TempDir Path output) throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        Process process = new ProcessBuilder(java, "tools/SyntheticPdfGenerator.java", output.toAbsolutePath().toString())
                .directory(SAMPLE_DATA.getParent().toFile())
                .redirectErrorStream(true)
                .start();
        String log = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(2, TimeUnit.MINUTES)).isTrue();
        assertThat(process.exitValue()).as(log).isZero();

        Map<Path, byte[]> generated = filesUnder(output.resolve("pdf"));
        Map<Path, byte[]> committed = filesUnder(PDF);
        assertThat(generated.keySet()).containsExactlyInAnyOrderElementsOf(committed.keySet());
        generated.forEach((file, bytes) -> assertThat(bytes).as(file.toString()).isEqualTo(committed.get(file)));
    }

    @Test
    void manifestListsEveryPdfOnceWithItsExpectations() throws IOException {
        List<ManifestEntry> manifest = manifest();

        assertThat(manifest.stream().map(ManifestEntry::file)).containsExactlyInAnyOrderElementsOf(pdfFiles().toList());
        assertThat(manifest).filteredOn(e -> !e.edgeCase()).hasSize(6)
                .allMatch(e -> e.recognized() && !e.answerKeyFile().isEmpty() && e.status().equals("RECONCILED"));
        assertThat(manifest).filteredOn(ManifestEntry::edgeCase).hasSize(12);
        for (ManifestEntry entry : manifest) {
            Path expected = PDF.resolve(base(entry.file()) + ".expected.csv");
            Path lines = PDF.resolve(base(entry.file()) + ".lines.txt");
            assertThat(lines).as(entry.file()).isRegularFile();
            if (entry.recognized()) {
                assertThat(entry.unparsedReason()).isEmpty();
                assertThat(Files.exists(expected)).as(entry.file()).isEqualTo(entry.edgeCase());
            } else {
                assertThat(entry.parser()).isEqualTo("none");
                assertThat(entry.unparsedReason()).isIn("UNRECOGNIZED_LAYOUT", "NO_TEXT_LAYER");
                assertThat(expected).doesNotExist();
            }
        }
    }

    // ------------------------------------------------------------------ extraction

    /** The extractor reads back exactly the lines the generator drew (docs/plans/phase-3b.md §1.3). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("pdfFiles")
    void extractedLinesMatchTheGeneratorsLines(String file) throws IOException {
        List<String> golden = Files.readAllLines(PDF.resolve(base(file) + ".lines.txt"));

        assertThat(goldenFormat(extract(file))).containsExactlyElementsOf(golden);
    }

    /** Compressing every content stream changes nothing the extractor reports. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("pdfFiles")
    void flateCompressedContentExtractsTheSame(String file) throws IOException {
        byte[] original = Files.readAllBytes(PDF.resolve(file));
        byte[] compressed;
        try (PDDocument document = Loader.loadPDF(original)) {
            for (PDPage page : document.getPages()) {
                byte[] content;
                try (var in = page.getContents()) {
                    content = in.readAllBytes();
                }
                page.setContents(new PDStream(document, new ByteArrayInputStream(content), COSName.FLATE_DECODE));
            }
            compressed = TestPdfs.save(document);
        }

        assertThat(new String(compressed, StandardCharsets.ISO_8859_1)).contains("/FlateDecode");
        assertThat(extractor.extract(ImportFile.of("compressed.pdf", compressed))).isEqualTo(extract(file));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pdfFiles")
    void everyPageIsMarkedInHeaderAndFooter(String file) {
        PdfDocumentText text = extract(file);

        assertThat(text.pages()).isNotEmpty();
        for (PdfPage page : text.pages()) {
            assertThat(page.lines()).as("page %d of %s", page.number(), file).isNotEmpty();
            assertThat(page.lines().getFirst().text()).isEqualTo(MARK);
            assertThat(page.lines().getLast().text()).isEqualTo(MARK);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pdfFiles")
    void noRealBankNameAppears(String file) throws IOException {
        String bytes = new String(Files.readAllBytes(PDF.resolve(file)), StandardCharsets.ISO_8859_1);
        String lines = Files.readString(PDF.resolve(base(file) + ".lines.txt"));
        Path expected = PDF.resolve(base(file) + ".expected.csv");
        String extracted = extract(file).lines().stream().map(PdfLine::text).collect(Collectors.joining("\n"));

        for (String content : List.of(bytes, lines, extracted, Files.exists(expected) ? Files.readString(expected) : "")) {
            assertThat(REAL_BANK_NAMES.matcher(content).find()).as(file).isFalse();
        }
    }

    // ------------------------------------------------------------------ monthly statements

    /** Each monthly PDF (and the glyph-by-glyph April copy) prints exactly its answer-key rows and balances. */
    @Test
    void answerKeyStatementsPrintTheirAnswerKeyRows() throws IOException {
        Map<String, List<AnswerKeyRow>> answerKey = answerKey();
        List<ManifestEntry> fromAnswerKey = manifest().stream().filter(e -> !e.answerKeyFile().isEmpty()).toList();

        assertThat(fromAnswerKey).hasSize(7);
        for (ManifestEntry entry : fromAnswerKey) {
            List<AnswerKeyRow> expected = answerKey.get(entry.answerKeyFile());
            assertThat(expected).as(entry.file()).isNotEmpty();
            BigDecimal sum = expected.stream().map(AnswerKeyRow::amount)
                    .reduce(new BigDecimal(entry.openingBalance()), BigDecimal::add);
            assertThat(sum).as(entry.file()).isEqualByComparingTo(entry.closingBalance());

            PdfDocumentText text = extract(entry.file());
            assertThat(printedRows(text)).as(entry.file()).containsExactlyInAnyOrderElementsOf(expected);
            List<String> lines = text.lines().stream().map(PdfLine::text).toList();
            assertThat(lines).contains(
                    "for " + longDate(entry.periodStart()) + " to " + longDate(entry.periodEnd()),
                    "Beginning balance on " + longDate(entry.periodStart()) + " " + balance(entry.openingBalance()),
                    "Ending balance on " + longDate(entry.periodEnd()) + " " + balance(entry.closingBalance()));
        }
    }

    /** Minimal reader: a row line starts with a date and ends with an amount; wraps start at the description column. */
    private static List<AnswerKeyRow> printedRows(PdfDocumentText text) {
        List<AnswerKeyRow> rows = new ArrayList<>();
        List<PdfLine> lines = text.lines();
        for (int i = 0; i < lines.size(); i++) {
            List<PdfWord> words = lines.get(i).words();
            if (words.size() < 3 || !ROW_DATE_WORD.matcher(words.getFirst().text()).matches()
                    || !AMOUNT_WORD.matcher(words.getLast().text()).matches()) {
                continue;
            }
            StringBuilder description = new StringBuilder(words.subList(1, words.size() - 1).stream()
                    .map(PdfWord::text).collect(Collectors.joining(" ")));
            for (int j = i + 1; j < lines.size() && lines.get(j).page() == lines.get(i).page()
                    && Math.abs(lines.get(j).words().getFirst().x() - DESCRIPTION_X) < 3; j++) {
                description.append(' ').append(lines.get(j).text());
            }
            rows.add(new AnswerKeyRow(LocalDate.parse(words.getFirst().text(), ROW_DATE), description.toString(),
                    new BigDecimal(words.getLast().text().replace(",", ""))));
        }
        return rows;
    }

    // ------------------------------------------------------------------ edge cases

    /** Opening + rows = closing; totals and summary equal the row sums except where a fixture says otherwise. */
    @Test
    void edgeCaseExpectationsAreConsistent() throws IOException {
        for (ManifestEntry entry : manifest().stream().filter(e -> e.recognized() && e.edgeCase()).toList()) {
            List<CSVRecord> records = expected(entry.file());
            String name = base(entry.file());

            assertThat(value(records, "period_start", "printed_date")).isEqualTo(entry.periodStart());
            assertThat(value(records, "period_end", "printed_date")).isEqualTo(entry.periodEnd());
            assertThat(value(records, "opening_balance", "amount")).isEqualTo(entry.openingBalance());
            assertThat(value(records, "closing_balance", "amount")).isEqualTo(entry.closingBalance());

            List<CSVRecord> rows = records.stream().filter(r -> r.get("record").equals("row")).toList();
            BigDecimal closing = rows.stream().map(r -> new BigDecimal(r.get("amount")))
                    .reduce(new BigDecimal(entry.openingBalance()), BigDecimal::add);
            assertThat(closing).as(name).isEqualByComparingTo(entry.closingBalance());

            for (String section : List.of("DEPOSIT", "WITHDRAWAL", "CHECK", "FEE")) {
                BigDecimal rowSum = rows.stream().filter(r -> r.get("section").equals(section))
                        .map(r -> new BigDecimal(r.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal total = new BigDecimal(sectionValue(records, "section_total", section));
                BigDecimal summary = new BigDecimal(sectionValue(records, "summary", section));
                boolean totalOff = name.endsWith("pdf-section-total-mismatch") && section.equals("WITHDRAWAL");
                boolean summaryOff = name.endsWith("pdf-summary-disagrees") && section.equals("DEPOSIT");

                assertThat(total.compareTo(rowSum) == 0).as("%s %s total", name, section).isEqualTo(!totalOff);
                assertThat(summary.compareTo(total) == 0).as("%s %s summary", name, section).isEqualTo(!summaryOff);
                for (CSVRecord row : rows.stream().filter(r -> r.get("section").equals(section)).toList()) {
                    int sign = new BigDecimal(row.get("amount")).signum();
                    assertThat(section.equals("DEPOSIT") ? sign >= 0 : sign <= 0).as("%s sign", name).isTrue();
                }
            }
            boolean warned = records.stream().anyMatch(r -> r.get("record").equals("warning"));
            assertThat(entry.status()).as(name).isEqualTo(warned ? "NEEDS_REVIEW" : "RECONCILED");
        }
    }

    /** Every expected row, total, summary and balance points at the extracted line that prints it. */
    @Test
    void edgeCaseExpectationsPointAtTheirPrintedLines() throws IOException {
        for (ManifestEntry entry : manifest().stream().filter(e -> e.recognized() && e.edgeCase()).toList()) {
            Map<Integer, PdfLine> lines = new LinkedHashMap<>();
            extract(entry.file()).lines().forEach(line -> lines.put(line.number(), line));

            for (CSVRecord record : expected(entry.file())) {
                String kind = record.get("record");
                String text = record.get("line").isEmpty() ? null : lines.get(Integer.parseInt(record.get("line"))).text();
                String as = base(entry.file()) + " " + record;
                switch (kind) {
                    case "period_start", "period_end" ->
                            assertThat(text).as(as).startsWith("for ").contains(longDate(record.get("printed_date")));
                    case "opening_balance", "closing_balance", "summary" ->
                            assertThat(text).as(as).endsWith(" " + record.get("printed_amount"));
                    case "section_total" -> {
                        if (text != null) {
                            assertThat(text).as(as).startsWith("Total ").endsWith(" " + record.get("printed_amount"));
                        } else {
                            assertThat(record.get("printed_amount")).as(as).isEmpty();
                        }
                    }
                    case "row" -> assertRowIsPrinted(record, lines, as);
                    case "warning" -> assertThat(text).as(as).isNotNull();
                    default -> throw new AssertionError("unknown record " + kind);
                }
            }
        }
    }

    private static void assertRowIsPrinted(CSVRecord row, Map<Integer, PdfLine> lines, String as) {
        int number = Integer.parseInt(row.get("line"));
        String date = ROW_DATE.format(LocalDate.parse(row.get("printed_date")));
        List<String> description = List.of(row.get("raw_description").split("\\\\n", -1));
        if (row.get("section").equals("CHECK")) {
            assertThat(row.get("check_number")).isEqualTo(description.getFirst().replace("*", ""));
            assertThat(" " + lines.get(number).text() + " ").as(as)
                    .contains(" " + date + " " + description.getFirst() + " " + row.get("printed_amount") + " ");
            return;
        }
        assertThat(lines.get(number).text()).as(as)
                .isEqualTo(date + " " + description.getFirst() + " " + row.get("printed_amount"));
        for (int i = 1; i < description.size(); i++) {
            assertThat(lines.get(number + i).text()).as(as).isEqualTo(description.get(i));
        }
    }

    // ------------------------------------------------------------------ helpers

    private PdfDocumentText extract(String file) {
        try {
            return extractor.extract(ImportFile.of(Path.of(file).getFileName().toString(),
                    Files.readAllBytes(PDF.resolve(file))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> goldenFormat(PdfDocumentText text) {
        return text.lines().stream()
                .map(line -> String.format(Locale.ROOT, "p%d L%04d %s", line.page(), line.number(), line.text()))
                .toList();
    }

    private static List<ManifestEntry> manifest() throws IOException {
        return parseCsv(Files.readString(PDF.resolve("pdf-manifest.csv"))).stream()
                .map(r -> new ManifestEntry(r.get("file"), r.get("parser"), r.get("unparsed_reason"),
                        r.get("answer_key_file"), r.get("period_start"), r.get("period_end"),
                        r.get("opening_balance"), r.get("closing_balance"), r.get("status")))
                .toList();
    }

    private static Map<String, List<AnswerKeyRow>> answerKey() throws IOException {
        Map<String, List<AnswerKeyRow>> rows = new LinkedHashMap<>();
        for (CSVRecord r : parseCsv(Files.readString(SAMPLE_DATA.resolve("answer-key.csv")))) {
            rows.computeIfAbsent(r.get("file"), file -> new ArrayList<>())
                    .add(new AnswerKeyRow(LocalDate.parse(r.get("date")), r.get("description"),
                            new BigDecimal(r.get("amount"))));
        }
        return rows;
    }

    private static List<CSVRecord> expected(String file) throws IOException {
        return parseCsv(Files.readString(PDF.resolve(base(file) + ".expected.csv")));
    }

    private static String value(List<CSVRecord> records, String record, String column) {
        return records.stream().filter(r -> r.get("record").equals(record)).findFirst().orElseThrow().get(column);
    }

    private static String sectionValue(List<CSVRecord> records, String record, String section) {
        return records.stream().filter(r -> r.get("record").equals(record) && r.get("section").equals(section))
                .findFirst().orElseThrow().get("amount");
    }

    private static List<CSVRecord> parseCsv(String content) throws IOException {
        try (Reader reader = new StringReader(content)) {
            return CSVFormat.Builder.create(CSVFormat.DEFAULT).setHeader().setSkipHeaderRecord(true).get()
                    .parse(reader).getRecords();
        }
    }

    private static String longDate(String isoDate) {
        return LONG_DATE.format(LocalDate.parse(isoDate));
    }

    private static String balance(String amount) {
        BigDecimal value = new BigDecimal(amount);
        return (value.signum() < 0 ? "-$" : "$") + String.format(Locale.ROOT, "%,.2f", value.abs());
    }

    private static String base(String file) {
        return file.substring(0, file.length() - ".pdf".length());
    }

    private static Map<Path, byte[]> filesUnder(Path root) throws IOException {
        Map<Path, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(root.relativize(file), Files.readAllBytes(file));
            }
        }
        return files;
    }
}
