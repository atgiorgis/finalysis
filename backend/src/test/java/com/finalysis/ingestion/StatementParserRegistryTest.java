package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ingestion.StatementParserRegistry.AmbiguousFormatException;
import com.finalysis.ingestion.StatementParserRegistry.UnsupportedFormatException;
import com.finalysis.ingestion.pdf.PdfDocumentText;
import com.finalysis.ingestion.pdf.PdfStatementParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StatementParserRegistryTest {

    private static final ImportFile FILE = ImportFile.of("statement.txt", "content".getBytes(StandardCharsets.UTF_8));
    private static final PdfDocumentText PDF_TEXT = new PdfDocumentText(List.of(), 0, 0);

    @Test
    void noMatchIsUnsupported() {
        StatementParserRegistry registry = new StatementParserRegistry(List.of(
                parser("ofx", FormatMatch.NONE), parser("csv", FormatMatch.NONE)));

        assertThatThrownBy(() -> registry.select(FILE)).isInstanceOf(UnsupportedFormatException.class);
    }

    @Test
    void noParsersAtAllIsUnsupported() {
        assertThatThrownBy(() -> new StatementParserRegistry(List.of()).select(FILE))
                .isInstanceOf(UnsupportedFormatException.class);
    }

    @Test
    void singleDefiniteMatchIsSelected() {
        StatementParser ofx = parser("ofx", FormatMatch.DEFINITE);
        StatementParserRegistry registry = new StatementParserRegistry(List.of(parser("pdf", FormatMatch.NONE), ofx));

        assertThat(registry.select(FILE)).isSameAs(ofx);
    }

    @Test
    void definiteMatchBeatsFallback() {
        StatementParser ofx = parser("ofx", FormatMatch.DEFINITE);
        StatementParserRegistry registry = new StatementParserRegistry(List.of(parser("csv", FormatMatch.FALLBACK), ofx));

        assertThat(registry.select(FILE)).isSameAs(ofx);
    }

    @Test
    void fallbackIsSelectedWhenNothingMatchesDefinitely() {
        StatementParser csv = parser("csv", FormatMatch.FALLBACK);
        StatementParserRegistry registry = new StatementParserRegistry(List.of(parser("ofx", FormatMatch.NONE), csv));

        assertThat(registry.select(FILE)).isSameAs(csv);
    }

    @Test
    void twoDefiniteMatchesAreAmbiguousEvenWithAFallback() {
        StatementParserRegistry registry = new StatementParserRegistry(List.of(
                parser("pdf-b", FormatMatch.DEFINITE), parser("csv", FormatMatch.FALLBACK), parser("pdf-a", FormatMatch.DEFINITE)));

        assertThatThrownBy(() -> registry.select(FILE))
                .isInstanceOfSatisfying(AmbiguousFormatException.class,
                        e -> assertThat(e.getParserIds()).containsExactly("pdf-a", "pdf-b"));
    }

    @Test
    void twoFallbackMatchesAreAmbiguous() {
        StatementParserRegistry registry = new StatementParserRegistry(List.of(
                parser("csv", FormatMatch.FALLBACK), parser("tsv", FormatMatch.FALLBACK)));

        assertThatThrownBy(() -> registry.select(FILE)).isInstanceOf(AmbiguousFormatException.class);
    }

    @Test
    void duplicateParserIdsFailAtStartup() {
        assertThatThrownBy(() -> new StatementParserRegistry(List.of(
                parser("ofx", FormatMatch.NONE), parser("ofx", FormatMatch.NONE))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ofx");
    }

    @Test
    void importFileHashesContentAndPrintsNoFileName() {
        ImportFile file = ImportFile.of("Jane Doe checking.csv", "abc".getBytes(StandardCharsets.UTF_8));

        assertThat(file.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(file.toString()).doesNotContain("Jane").contains("3 bytes");
    }

    // ------------------------------------------------------------------ routing by kind (docs/plans/phase-3b.md §1.6)

    @Test
    void textFilesAreNeverOfferedToPdfParsers() {
        CountingPdfParser pdf = new CountingPdfParser("pdf-layout", FormatMatch.DEFINITE);
        StatementParser csv = parser("csv", FormatMatch.FALLBACK);
        StatementParserRegistry registry = new StatementParserRegistry(List.of(pdf, csv));

        assertThat(registry.select(FILE)).isSameAs(csv);
        assertThat(pdf.calls).hasValue(0);
    }

    @Test
    void aTextFileOnlyPdfParsersWouldClaimIsUnsupported() {
        StatementParserRegistry registry = new StatementParserRegistry(List.of(
                new CountingPdfParser("pdf-layout", FormatMatch.DEFINITE)));

        assertThatThrownBy(() -> registry.select(FILE)).isInstanceOf(UnsupportedFormatException.class);
    }

    @Test
    void pdfTextIsOfferedOnlyToPdfParsers() {
        AtomicInteger textCalls = new AtomicInteger();
        StatementParser csv = new StatementParser() {
            @Override
            public String id() {
                return "csv";
            }

            @Override
            public FormatMatch detect(ImportFile file) {
                textCalls.incrementAndGet();
                return FormatMatch.FALLBACK;
            }

            @Override
            public ParsedStatement parse(ImportFile file, ParseContext context) {
                throw new UnsupportedOperationException();
            }
        };
        CountingPdfParser layout = new CountingPdfParser("pdf-layout", FormatMatch.DEFINITE);
        StatementParserRegistry registry = new StatementParserRegistry(List.of(csv, layout));

        assertThat(registry.selectPdf(PDF_TEXT)).containsSame(layout);
        assertThat(layout.calls).hasValue(1);
        assertThat(textCalls).hasValue(0);
    }

    @Test
    void noPdfParserClaimingTheTextIsEmptyNotAnError() {
        StatementParserRegistry registry = new StatementParserRegistry(List.of(
                parser("csv", FormatMatch.FALLBACK), new CountingPdfParser("pdf-layout", FormatMatch.NONE)));

        assertThat(registry.selectPdf(PDF_TEXT)).isEmpty();
        assertThat(new StatementParserRegistry(List.of()).selectPdf(PDF_TEXT)).isEmpty();
    }

    @Test
    void pdfLayoutsHaveNoFallbackLevel() {
        StatementParserRegistry registry = new StatementParserRegistry(List.of(
                new CountingPdfParser("pdf-layout", FormatMatch.FALLBACK)));

        assertThat(registry.selectPdf(PDF_TEXT)).isEmpty();
    }

    @Test
    void twoDefinitePdfParsersAreAmbiguous() {
        StatementParserRegistry registry = new StatementParserRegistry(List.of(
                new CountingPdfParser("pdf-b", FormatMatch.DEFINITE), new CountingPdfParser("pdf-a", FormatMatch.DEFINITE),
                new CountingPdfParser("pdf-c", FormatMatch.NONE)));

        assertThatThrownBy(() -> registry.selectPdf(PDF_TEXT))
                .isInstanceOfSatisfying(AmbiguousFormatException.class,
                        e -> assertThat(e.getParserIds()).containsExactly("pdf-a", "pdf-b"));
    }

    @Test
    void pdfAndTextParsersShareOneIdSpace() {
        assertThatThrownBy(() -> new StatementParserRegistry(List.of(
                parser("pdf-layout", FormatMatch.NONE), new CountingPdfParser("pdf-layout", FormatMatch.NONE))))
                .isInstanceOf(IllegalStateException.class);
    }

    /** A PDF layout parser that counts how often it is asked; its text-file detection would claim anything. */
    private static final class CountingPdfParser implements PdfStatementParser {

        private final String id;
        private final FormatMatch match;
        private final AtomicInteger calls = new AtomicInteger();

        CountingPdfParser(String id, FormatMatch match) {
            this.id = id;
            this.match = match;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public FormatMatch detect(PdfDocumentText text) {
            calls.incrementAndGet();
            return match;
        }

        @Override
        public FormatMatch detect(ImportFile file) {
            calls.incrementAndGet();
            return match;
        }

        @Override
        public ParsedStatement parse(PdfDocumentText text, ParseContext context) {
            throw new UnsupportedOperationException();
        }
    }

    private static StatementParser parser(String id, FormatMatch match) {
        return new StatementParser() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public FormatMatch detect(ImportFile file) {
                return match;
            }

            @Override
            public ParsedStatement parse(ImportFile file, ParseContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
