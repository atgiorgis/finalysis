package com.finalysis.ingestion.pdf.boa;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.ingestion.ImportFile;
import com.finalysis.ingestion.SourceSection;
import com.finalysis.ingestion.pdf.PdfDocumentText;
import com.finalysis.ingestion.pdf.PdfLine;
import com.finalysis.ingestion.pdf.PdfPage;
import com.finalysis.ingestion.pdf.PdfTextExtractor;
import com.finalysis.ingestion.pdf.PdfWord;
import com.finalysis.ingestion.pdf.boa.BankOfAmericaLineClassifier.ClassifiedLine;
import com.finalysis.ingestion.pdf.boa.BankOfAmericaLineClassifier.LineKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Line classification for the Bank of America checking layout (docs/plans/phase-3b.md §3.1, §3.3).
 * Every line of every synthetic statement gets the kind the generator drew it as (its
 * {@code .labels.txt}), and the continuation rules hold at their boundaries.
 */
class BankOfAmericaLineClassifierTest {

    static final Path PDF = Path.of("..", "sample-data", "pdf");

    private static final float LEFT = 36;
    private static final float DESCRIPTION = 90;
    private static final float RIGHT = 576;

    /** The fixtures in this layout, from {@code pdf-manifest.csv}. */
    static Stream<String> layoutFixtures() throws IOException {
        return Files.readAllLines(PDF.resolve("pdf-manifest.csv")).stream().skip(1)
                .map(line -> line.split(",", -1))
                .filter(c -> c[1].equals(BankOfAmericaCheckingParser.ID))
                .map(c -> c[0]);
    }

    static PdfDocumentText extract(String fixture) throws IOException {
        byte[] bytes = Files.readAllBytes(PDF.resolve(fixture));
        return new PdfTextExtractor().extract(ImportFile.of(Path.of(fixture).getFileName().toString(), bytes));
    }

    // ------------------------------------------------------------------ the fixtures

    @ParameterizedTest(name = "{0}")
    @MethodSource("layoutFixtures")
    void everyLineGetsTheKindTheGeneratorDrewItAs(String fixture) throws IOException {
        List<String> expected = Files.readAllLines(PDF.resolve(fixture.replace(".pdf", ".labels.txt")));

        List<String> actual = BankOfAmericaLineClassifier.classify(extract(fixture)).stream()
                .map(c -> String.format(Locale.ROOT, "p%d L%04d %s", c.line().page(), c.line().number(), c.kind()))
                .toList();

        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void theFixturesExerciseEveryKind() throws IOException {
        Set<String> seen = new HashSet<>();
        for (String fixture : layoutFixtures().toList()) {
            Files.readAllLines(PDF.resolve(fixture.replace(".pdf", ".labels.txt")))
                    .forEach(line -> seen.add(line.substring(line.lastIndexOf(' ') + 1)));
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(LineKind.values()).map(Enum::name).toList());
    }

    @Test
    void sectionLinesCarryTheirSectionAcrossPages() throws IOException {
        Map<Integer, ClassifiedLine> april = byNumber(extract("northfield-checking_2026-04.pdf"));

        assertThat(april.get(10).section()).as("beginning balance").isNull();
        assertThat(april.get(11).section()).isEqualTo(SourceSection.DEPOSIT);
        assertThat(april.get(13).section()).isEqualTo(SourceSection.CHECK);
        assertThat(april.get(14).section()).isEqualTo(SourceSection.FEE);
        assertThat(april.get(15).section()).as("ending balance").isNull();
        for (int line : new int[] {16, 17, 18, 19, 22}) {
            assertThat(april.get(line).section()).as("line %d", line).isEqualTo(SourceSection.DEPOSIT);
        }
        for (int line : new int[] {23, 25, 26, 33, 39, 40, 41, 42}) {
            assertThat(april.get(line).section()).as("line %d", line).isEqualTo(SourceSection.WITHDRAWAL);
        }
        assertThat(april.get(34).section()).as("page footer").isNull();
    }

    @Test
    void checkRowsAreInTheChecksSection() throws IOException {
        List<ClassifiedLine> checks = BankOfAmericaLineClassifier.classify(extract("edge-cases/pdf-checks-side-by-side.pdf"))
                .stream().filter(c -> c.kind() == LineKind.CHECK_ROW).toList();

        assertThat(checks).hasSize(3).allMatch(c -> c.section() == SourceSection.CHECK);
    }

    @Test
    void theFooterNoteJustBelowARowIsNotGluedOn() throws IOException {
        Map<Integer, ClassifiedLine> wrapped = byNumber(extract("edge-cases/pdf-wrapped-descriptions.pdf"));

        // Line 40 starts at the description column, directly below a row, two pitches down.
        assertThat(wrapped.get(39).kind()).isEqualTo(LineKind.TXN);
        assertThat(wrapped.get(40).kind()).isEqualTo(LineKind.PAGE_FOOTER);
    }

    // ------------------------------------------------------------------ continuation rules (§3.3)

    /**
     * Rows with pitches 13 (from the column header), 12 and 12: a median of 12, so a continuation
     * may be at most 19.2 pt below; its first word must start within 3 pt of the description column.
     */
    @ParameterizedTest(name = "{0} pt below, at x={1}: {2}")
    @CsvSource({
            "12, 90, CONTINUATION",
            "19.2, 90, CONTINUATION",
            "19.5, 90, OTHER",
            "12, 93, CONTINUATION",
            "12, 87, CONTINUATION",
            "12, 93.5, OTHER",
            "12, 86.5, OTHER",
            "12, 36, OTHER"})
    void aContinuationIsCloseBelowAndAtTheDescriptionColumn(float gap, float x, LineKind expected) {
        Doc doc = withdrawals();
        doc.line(151 + gap, word(x, "ID:"), word(x + 20, "4419920113"));
        doc.total(190);

        assertThat(doc.classify().get(5).kind()).isEqualTo(expected);
    }

    @Test
    void aLineWithAnAmountInTheAmountColumnIsNotAContinuation() {
        Doc doc = withdrawals();
        doc.line(163, word(DESCRIPTION, "REFUND"), right(RIGHT, "5.00"));
        doc.total(180);

        assertThat(doc.classify().get(5).kind()).isEqualTo(LineKind.OTHER);
    }

    @Test
    void aLineWithADateInTheDateColumnIsNotAContinuation() {
        Doc doc = withdrawals();
        doc.line(163, word(LEFT, "04/09/26"), word(DESCRIPTION, "NO AMOUNT"));
        doc.total(180);

        assertThat(doc.classify().get(5).kind()).isEqualTo(LineKind.OTHER);
    }

    @Test
    void continuationsChainButOnlyBelowARowOrContinuation() {
        Doc doc = withdrawals();
        doc.line(163, word(DESCRIPTION, "FIRST"));
        doc.line(175, word(DESCRIPTION, "SECOND"));
        doc.total(191);
        doc.line(203, word(DESCRIPTION, "AFTER THE TOTAL"));
        doc.line(215, word(LEFT, "Total"), word(80, "Wine"));
        doc.total(240);

        List<ClassifiedLine> lines = doc.classify();
        assertThat(lines.subList(5, 8)).extracting(ClassifiedLine::kind)
                .containsExactly(LineKind.CONTINUATION, LineKind.CONTINUATION, LineKind.TOTAL);
        assertThat(lines.get(8).kind()).as("after the total, no section is open").isEqualTo(LineKind.OTHER);
    }

    @Test
    void aWrapIsNeverCarriedAcrossAPage() {
        Doc doc = withdrawals();
        doc.page();
        doc.line(60, word(DESCRIPTION, "WRAPPED"));
        doc.line(72, word(LEFT, "Withdrawals and other subtractions - continued"));
        doc.header(86);
        doc.row(99, "04/20/26", "-4.00");
        doc.total(115);

        List<ClassifiedLine> lines = doc.classify();
        assertThat(lines.get(5).kind()).isEqualTo(LineKind.PAGE_HEADER);
        assertThat(lines.get(6).kind()).isEqualTo(LineKind.SECTION_CONTINUED);
        assertThat(lines.get(8).section()).isEqualTo(SourceSection.WITHDRAWAL);
    }

    // ------------------------------------------------------------------ shapes left to the parser

    @Test
    void rowsUnderAnUnknownHeadingAreRowsInNoSection() {
        Doc doc = new Doc();
        doc.line(100, word(LEFT, "ATM and debit card subtractions"));
        doc.header(114);
        doc.row(127, "04/01/26", "-1.00");

        List<ClassifiedLine> lines = doc.classify();
        assertThat(lines).extracting(ClassifiedLine::kind)
                .containsExactly(LineKind.OTHER, LineKind.COLUMN_HEADER, LineKind.TXN);
        assertThat(lines).allMatch(line -> line.section() == null);
    }

    @Test
    void aCheckLineWithAWordLeftOverIsNotACheckRow() {
        Doc doc = new Doc();
        doc.line(100, word(LEFT, "Checks"));
        doc.line(114, word(LEFT, "Date"), word(96, "Check"), word(122, "#"), right(260, "Amount"),
                word(320, "Date"), word(380, "Check"), word(406, "#"), right(RIGHT, "Amount"));
        doc.line(127, word(LEFT, "04/02/26"), word(96, "1234"), right(260, "-1.00"));
        doc.line(139, word(LEFT, "04/02/26"), word(96, "1235"), right(260, "-1.00"), word(320, "VOID"));
        doc.line(151, word(320, "04/02/26"), word(380, "1236"), right(RIGHT, "-1.00"));
        doc.line(167, word(LEFT, "Total checks"), right(RIGHT, "-$3.00"));

        assertThat(doc.classify()).extracting(ClassifiedLine::kind).containsExactly(LineKind.SECTION_START,
                LineKind.COLUMN_HEADER, LineKind.CHECK_ROW, LineKind.OTHER, LineKind.OTHER, LineKind.TOTAL);
    }

    @Test
    void aDocumentWithoutTextHasNoLines() {
        assertThat(BankOfAmericaLineClassifier.classify(new PdfDocumentText(List.of(), 0, 0))).isEmpty();
        assertThat(BankOfAmericaLineClassifier.markers(new PdfDocumentText(List.of(), 0, 0)).all()).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private static Map<Integer, ClassifiedLine> byNumber(PdfDocumentText text) {
        return BankOfAmericaLineClassifier.classify(text).stream()
                .collect(Collectors.toMap(c -> c.line().number(), c -> c));
    }

    /** A withdrawals section with three rows at y = 127, 139 and 151 (lines 1 to 5). */
    private static Doc withdrawals() {
        Doc doc = new Doc();
        doc.line(100, word(LEFT, "Withdrawals and other subtractions"));
        doc.header(114);
        doc.row(127, "04/01/26", "-1.00");
        doc.row(139, "04/02/26", "-1.00");
        doc.row(151, "04/03/26", "-1.00");
        return doc;
    }

    /** A word about 5 pt per character wide; multi-word text is split into words. */
    private static PdfWord[] word(float x, String text) {
        List<PdfWord> words = new ArrayList<>();
        float at = x;
        for (String part : text.split(" ")) {
            words.add(new PdfWord(part, at, at + 5 * part.length(), 0, 9, false));
            at += 5 * part.length() + 3;
        }
        return words.toArray(PdfWord[]::new);
    }

    /** A word right-aligned to {@code xEnd}. */
    private static PdfWord[] right(float xEnd, String text) {
        return new PdfWord[] {new PdfWord(text, xEnd - 5 * text.length(), xEnd, 0, 9, false)};
    }

    /** Builds a document line by line; line numbers follow the order lines are added. */
    private static final class Doc {
        private final List<List<PdfLine>> pages = new ArrayList<>(List.of(new ArrayList<>()));
        private int number;

        void line(float y, PdfWord[]... parts) {
            List<PdfWord> words = Stream.of(parts).flatMap(Arrays::stream)
                    .map(w -> new PdfWord(w.text(), w.x(), w.xEnd(), y, w.fontSize(), w.bold())).toList();
            pages.getLast().add(new PdfLine(++number, pages.size(), y, words));
        }

        void header(float y) {
            line(y, word(LEFT, "Date"), word(DESCRIPTION, "Description"), right(RIGHT, "Amount"));
        }

        void row(float y, String date, String amount) {
            line(y, word(LEFT, date), word(DESCRIPTION, "SAMPLE MERCHANT"), right(RIGHT, amount));
        }

        void total(float y) {
            line(y, word(LEFT, "Total withdrawals and other subtractions"), right(RIGHT, "-$3.00"));
        }

        void page() {
            pages.add(new ArrayList<>());
        }

        List<ClassifiedLine> classify() {
            List<PdfPage> built = new ArrayList<>();
            for (int i = 0; i < pages.size(); i++) {
                built.add(new PdfPage(i + 1, 612, 792, pages.get(i)));
            }
            return BankOfAmericaLineClassifier.classify(new PdfDocumentText(built, 0, 0));
        }
    }
}
