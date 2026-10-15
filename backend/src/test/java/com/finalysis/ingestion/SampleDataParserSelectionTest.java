package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.finalysis.ingestion.csv.CsvStatementParser;
import com.finalysis.ingestion.ofx.OfxStatementParser;
import com.finalysis.ingestion.pdf.PdfDocumentText;
import com.finalysis.ingestion.pdf.PdfStatementParser;
import com.finalysis.ingestion.pdf.PdfTextExtractor;
import com.finalysis.ingestion.pdf.boa.BankOfAmericaCheckingParser;
import com.finalysis.support.ImportTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every file under {@code sample-data/}, broken and edge cases included, goes where its format
 * says, routed by content as the import routes it (docs/plans/phase-3b.md §1.6):
 * <ul>
 *   <li>text files are claimed by exactly one text parser: OFX/QFX files by the OFX parser (a
 *       definite match beats the CSV fallback), everything else by the CSV parser;</li>
 *   <li>PDFs are offered only to PDF layout parsers, never to the CSV or OFX parser, and end as
 *       {@code pdf/pdf-manifest.csv} says: claimed by the named parser, or an unparsed upload with
 *       the named reason.</li>
 * </ul>
 * Detection is by content only; the extension just names the expectation.
 */
class SampleDataParserSelectionTest {

    private static final Path PDF = ImportTestSupport.SAMPLE_DATA.resolve("pdf");

    private final AtomicInteger textParserCalls = new AtomicInteger();
    private final StatementParserRegistry registry = new StatementParserRegistry(List.of(
            counted(new OfxStatementParser()), counted(new CsvStatementParser()), new BankOfAmericaCheckingParser()));

    record PdfExpectation(String file, String parser, String unparsedReason) {
        @Override
        public String toString() {
            return file;
        }
    }

    static Stream<Path> sampleFiles() throws IOException {
        try (Stream<Path> files = Files.walk(ImportTestSupport.SAMPLE_DATA)) {
            return files.filter(Files::isRegularFile)
                    .map(ImportTestSupport.SAMPLE_DATA::relativize)
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    /** Text files: everything except PDFs and the PDF generator's own expectation files. */
    static Stream<Path> textSampleFiles() throws IOException {
        return sampleFiles().filter(path -> !isPdf(path) && !isPdfExpectation(path));
    }

    static Stream<PdfExpectation> pdfManifest() throws IOException {
        return Files.readAllLines(PDF.resolve("pdf-manifest.csv")).stream().skip(1)
                .map(line -> line.split(",", -1))
                .map(c -> new PdfExpectation(c[0], c[1], c[2]));
    }

    @Test
    void sampleDataHasEveryKindOfFile() throws IOException {
        List<String> names = sampleFiles().map(Path::toString).toList();

        assertThat(names).anyMatch(name -> name.startsWith("broken/"))
                .anyMatch(name -> name.startsWith("edge-cases/csv-"))
                .anyMatch(name -> name.startsWith("edge-cases/ofx-"))
                .anyMatch(name -> name.startsWith("edge-cases/transfers/"))
                .anyMatch(name -> name.startsWith("edge-cases/merchants/"))
                .anyMatch(name -> name.startsWith("edge-cases/ai-categorization/"))
                .anyMatch(name -> name.startsWith("categorization/"))
                .anyMatch(name -> name.endsWith(".qfx"))
                .anyMatch(name -> name.startsWith("pdf/edge-cases/") && name.endsWith(".pdf"));
        // 25 CSV statements, 1 broken, 13 OFX/QFX, 4 OFX and 9 CSV edge cases, 3 transfer edge-case
        // statements and their expected outcomes, 1 merchant edge-case statement and its expected
        // merchants, manifest, answer key, categorization dev and holdout sets, AI categorization,
        // similar-merchant and eval harness cases
        assertThat(textSampleFiles()).hasSize(65);
        // 6 monthly PDFs and 12 edge cases
        assertThat(sampleFiles().filter(SampleDataParserSelectionTest::isPdf)).hasSize(18);
    }

    @Test
    void thePdfManifestListsEveryPdfOnce() throws IOException {
        List<String> listed = pdfManifest().map(e -> "pdf/" + e.file()).toList();

        assertThat(listed).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(
                sampleFiles().filter(SampleDataParserSelectionTest::isPdf).map(Path::toString).toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("textSampleFiles")
    void everyTextSampleFileResolvesToExactlyOneParser(Path relativePath) throws IOException {
        byte[] bytes = Files.readAllBytes(ImportTestSupport.SAMPLE_DATA.resolve(relativePath));
        ImportFile file = ImportFile.of(relativePath.getFileName().toString(), bytes);
        String name = relativePath.toString();
        String expected = name.endsWith(".ofx") || name.endsWith(".qfx") ? "ofx" : "csv";

        assertThat(UploadCheck.checkContent(bytes)).isEqualTo(FileKind.TEXT);
        assertThat(registry.select(file).id()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pdfManifest")
    void everySamplePdfIsRoutedOnlyToPdfParsersAndEndsAsTheManifestSays(PdfExpectation expected) throws IOException {
        byte[] bytes = Files.readAllBytes(PDF.resolve(expected.file()));
        String name = Path.of(expected.file()).getFileName().toString();

        assertThat(UploadCheck.checkContent(bytes)).isEqualTo(FileKind.PDF);
        assertThatCode(() -> UploadCheck.checkKindMatchesName(name, FileKind.PDF)).doesNotThrowAnyException();
        PdfDocumentText text = new PdfTextExtractor().extract(ImportFile.of(name, bytes));
        Optional<PdfStatementParser> parser = registry.selectPdf(text);

        assertThat(textParserCalls).as("CSV and OFX parsers asked about a PDF").hasValue(0);
        switch (expected.parser()) {
            case "none" -> {
                assertThat(parser).isEmpty();
                assertThat(UnparsedReason.of(text).name()).isEqualTo(expected.unparsedReason());
            }
            case BankOfAmericaCheckingParser.ID -> {
                assertThat(expected.unparsedReason()).isEmpty();
                assertThat(parser).map(StatementParser::id).contains(BankOfAmericaCheckingParser.ID);
                assertThat(parser.get().detect(text)).isEqualTo(FormatMatch.DEFINITE);
            }
            default -> throw new AssertionError("unknown parser " + expected.parser());
        }
    }

    private static boolean isPdf(Path relativePath) {
        return relativePath.toString().endsWith(".pdf");
    }

    /**
     * {@code tools/SyntheticPdfGenerator.java}'s manifest, expected rows, golden line texts and labels:
     * expectations about the PDFs, checked by {@code SyntheticPdfTest}, not statements.
     */
    private static boolean isPdfExpectation(Path relativePath) {
        String name = relativePath.getFileName().toString();
        return relativePath.startsWith("pdf") && (name.equals("pdf-manifest.csv") || name.endsWith(".expected.csv")
                || name.endsWith(".lines.txt") || name.endsWith(".labels.txt"));
    }

    /** The parser, counting every question asked of it. */
    private StatementParser counted(StatementParser parser) {
        return new StatementParser() {
            @Override
            public String id() {
                return parser.id();
            }

            @Override
            public FormatMatch detect(ImportFile file) {
                textParserCalls.incrementAndGet();
                return parser.detect(file);
            }

            @Override
            public boolean usesCsvMapping() {
                return parser.usesCsvMapping();
            }

            @Override
            public ParsedStatement parse(ImportFile file, ParseContext context) {
                textParserCalls.incrementAndGet();
                return parser.parse(file, context);
            }
        };
    }
}
