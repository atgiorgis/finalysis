package com.finalysis.ingestion.pdf.boa;

import static com.finalysis.ingestion.pdf.boa.BankOfAmericaLineClassifierTest.PDF;
import static com.finalysis.ingestion.pdf.boa.BankOfAmericaLineClassifierTest.extract;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.account.AccountType;
import com.finalysis.ingestion.FormatMatch;
import com.finalysis.ingestion.ImportFile;
import com.finalysis.ingestion.ParseContext;
import com.finalysis.ingestion.StatementParseException;
import com.finalysis.ingestion.pdf.PdfDocumentText;
import com.finalysis.ingestion.pdf.PdfLine;
import com.finalysis.ingestion.pdf.PdfPage;
import com.finalysis.ingestion.pdf.PdfWord;
import com.finalysis.ingestion.pdf.boa.BankOfAmericaLineClassifier.Markers;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Detection of the Bank of America checking layout by its structure (docs/plans/phase-3b.md §1.5):
 * every synthetic statement in the layout is DEFINITE, everything else NONE, removing or misplacing
 * any one marker gives NONE, and the institution's name plays no part.
 */
class BankOfAmericaCheckingParserTest {

    // April's page 1 lines (sample-data/pdf/northfield-checking_2026-04.lines.txt)
    private static final int INSTITUTION = 2;
    private static final int PERIOD = 7;
    private static final int BEGINNING = 10;
    private static final int DEPOSITS = 11;
    private static final int WITHDRAWALS = 12;
    private static final int ENDING = 15;

    private final BankOfAmericaCheckingParser parser = new BankOfAmericaCheckingParser();

    static Stream<Arguments> manifest() throws IOException {
        return Files.readAllLines(PDF.resolve("pdf-manifest.csv")).stream().skip(1)
                .map(line -> line.split(",", -1))
                .map(c -> Arguments.of(c[0], c[1].equals(BankOfAmericaCheckingParser.ID)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("manifest")
    void detectsExactlyTheStatementsTheManifestNames(String fixture, boolean inThisLayout) throws IOException {
        assertThat(parser.detect(extract(fixture))).isEqualTo(inThisLayout ? FormatMatch.DEFINITE : FormatMatch.NONE);
    }

    @Test
    void detectsFromTheFileToo() throws IOException {
        byte[] bytes = Files.readAllBytes(PDF.resolve("northfield-checking_2026-04.pdf"));

        assertThat(parser.detect(ImportFile.of("april.pdf", bytes))).isEqualTo(FormatMatch.DEFINITE);
        assertThat(parser.detect(ImportFile.of("april.pdf", "%PDF-1.4 broken".getBytes()))).isEqualTo(FormatMatch.NONE);
    }

    @Test
    void anEmptyDocumentIsNone() {
        assertThat(parser.detect(new PdfDocumentText(List.of(), 0, 0))).isEqualTo(FormatMatch.NONE);
    }

    // ------------------------------------------------------------------ markers

    static Stream<Arguments> markers() {
        return Stream.of(
                Arguments.of("period", PERIOD, new Markers(false, true, true, true, true)),
                Arguments.of("beginning balance", BEGINNING, new Markers(true, false, true, true, true)),
                Arguments.of("deposits", DEPOSITS, new Markers(true, true, false, true, true)),
                Arguments.of("withdrawals", WITHDRAWALS, new Markers(true, true, true, false, true)),
                Arguments.of("ending balance", ENDING, new Markers(true, true, true, true, false)));
    }

    @Test
    void aprilHasEveryMarker() throws IOException {
        assertThat(BankOfAmericaLineClassifier.markers(april())).isEqualTo(new Markers(true, true, true, true, true));
    }

    @ParameterizedTest(name = "without the {0} line")
    @MethodSource("markers")
    void removingAnyMarkerGivesNone(String marker, int line, Markers expected) throws IOException {
        PdfDocumentText text = onPage1(april(), lines -> lines.stream().filter(l -> l.number() != line).toList());

        assertThat(BankOfAmericaLineClassifier.markers(text)).isEqualTo(expected);
        assertThat(parser.detect(text)).isEqualTo(FormatMatch.NONE);
    }

    @ParameterizedTest(name = "the {0} line without its amount")
    @MethodSource("markers")
    void aMarkerWithoutItsAmountGivesNone(String marker, int line, Markers ignored) throws IOException {
        PdfDocumentText text = onPage1(april(), lines -> lines.stream()
                .map(l -> l.number() == line ? withoutLastWord(l) : l).toList());

        // The period line has no amount: dropping its last word breaks its end date instead.
        assertThat(parser.detect(text)).isEqualTo(FormatMatch.NONE);
    }

    @Test
    void theEndingBalanceAboveTheSectionLinesGivesNone() throws IOException {
        PdfDocumentText text = onPage1(april(), lines -> swap(lines, DEPOSITS, ENDING));

        assertThat(BankOfAmericaLineClassifier.markers(text).endingBalance()).isFalse();
        assertThat(parser.detect(text)).isEqualTo(FormatMatch.NONE);
    }

    @Test
    void aSectionLineAboveTheBeginningBalanceGivesNone() throws IOException {
        PdfDocumentText text = onPage1(april(), lines -> swap(lines, BEGINNING, WITHDRAWALS));

        assertThat(BankOfAmericaLineClassifier.markers(text).withdrawals()).isFalse();
        assertThat(parser.detect(text)).isEqualTo(FormatMatch.NONE);
    }

    @Test
    void markersAreReadOnPageOneOnly() throws IOException {
        PdfDocumentText april = april();
        PdfPage first = april.pages().getFirst();
        PdfLine mark = first.lines().getFirst();
        List<PdfPage> pages = new ArrayList<>();
        pages.add(new PdfPage(1, first.width(), first.height(), List.of(mark)));
        pages.add(new PdfPage(2, first.width(), first.height(), first.lines().subList(1, first.lines().size())));

        assertThat(parser.detect(new PdfDocumentText(pages, 0, 0))).isEqualTo(FormatMatch.NONE);
    }

    @Test
    void sectionHeadingsAreNotMarkers() throws IOException {
        // The no-activity statement prints no section at all.
        assertThat(parser.detect(extract("edge-cases/pdf-no-activity.pdf"))).isEqualTo(FormatMatch.DEFINITE);
    }

    // ------------------------------------------------------------------ never the institution's name

    @Test
    void theInstitutionLineIsIrrelevant() throws IOException {
        PdfDocumentText without = onPage1(april(), lines -> lines.stream().filter(l -> l.number() != INSTITUTION).toList());
        PdfDocumentText renamed = onPage1(april(), lines -> lines.stream()
                .map(l -> l.number() == INSTITUTION ? retext(l, "BANK OF AMERICA") : l).toList());

        assertThat(parser.detect(without)).isEqualTo(FormatMatch.DEFINITE);
        assertThat(parser.detect(renamed)).isEqualTo(FormatMatch.DEFINITE);
    }

    @Test
    void theInstitutionNameAloneIsNotEnough() throws IOException {
        PdfDocumentText card = onPage1(extract("edge-cases/pdf-unrecognized-layout.pdf"), lines -> lines.stream()
                .map(l -> l.number() == 2 ? retext(l, "Bank of America") : l).toList());

        assertThat(parser.detect(card)).isEqualTo(FormatMatch.NONE);
    }

    // ------------------------------------------------------------------ parse, until step 6

    @Test
    void parseSaysItIsNotImplementedWithoutQuotingTheFile() throws IOException {
        assertThatThrownBy(() -> parser.parse(april(), new ParseContext(AccountType.CHECKING)))
                .isInstanceOfSatisfying(StatementParseException.class, e -> {
                    assertThat(e.getLineNumber()).isNull();
                    assertThat(e.getMessage()).isEqualTo("The statement layout was recognized (boa-checking), "
                            + "but reading it is not implemented yet; nothing was imported");
                });
    }

    // ------------------------------------------------------------------ helpers

    private static PdfDocumentText april() throws IOException {
        return extract("northfield-checking_2026-04.pdf");
    }

    private static PdfDocumentText onPage1(PdfDocumentText text, UnaryOperator<List<PdfLine>> change) {
        List<PdfPage> pages = new ArrayList<>(text.pages());
        PdfPage first = pages.getFirst();
        pages.set(0, new PdfPage(1, first.width(), first.height(), change.apply(first.lines())));
        return new PdfDocumentText(pages, 0, 0);
    }

    private static PdfLine withoutLastWord(PdfLine line) {
        return new PdfLine(line.number(), line.page(), line.y(), line.words().subList(0, line.words().size() - 1));
    }

    private static PdfLine retext(PdfLine line, String text) {
        PdfWord first = line.words().getFirst();
        List<PdfWord> words = new ArrayList<>();
        float x = first.x();
        for (String part : text.split(" ")) {
            words.add(new PdfWord(part, x, x + 8 * part.length(), first.baselineY(), first.fontSize(), first.bold()));
            x += 8 * part.length() + 4;
        }
        return new PdfLine(line.number(), line.page(), line.y(), words);
    }

    /** The lines in order, with the two numbered lines trading places. */
    private static List<PdfLine> swap(List<PdfLine> lines, int a, int b) {
        List<PdfLine> swapped = new ArrayList<>(lines);
        int i = indexOf(lines, a);
        int j = indexOf(lines, b);
        Collections.swap(swapped, i, j);
        return swapped;
    }

    private static int indexOf(List<PdfLine> lines, int number) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).number() == number) {
                return i;
            }
        }
        throw new AssertionError("no line " + number);
    }
}
