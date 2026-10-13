package com.finalysis.ingestion.pdf;

import static com.finalysis.ingestion.pdf.TestPdfs.save;
import static com.finalysis.ingestion.pdf.TestPdfs.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.ingestion.pdf.boa.BankOfAmericaSkeletonLayout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.SequencedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The masked skeleton debug tool (docs/plans/phase-3b.md §6): kinds per fixture line, the masking
 * guarantee on every fixture with and without {@code --show-layout-words}, the summary, and the
 * command line. Every PDF is synthetic: the committed fixtures or PDFs built in memory.
 */
class PdfSkeletonTest {

    static final Path PDF = Path.of("..", "sample-data", "pdf");

    private static final PdfSkeletonLayout BOA = new BankOfAmericaSkeletonLayout();
    private static final PdfSkeleton SKELETON = new PdfSkeleton(PdfSkeletonCli.LAYOUTS);
    private static final String MARKER = "LEAKMARKER";

    /** {@code p1 L0018 TXN               @ 36 99/99/99 | @ 90 xxxx | @541 9,999.99}. */
    private static final Pattern ROW = Pattern.compile("p(\\d+) L(\\d{4}) ([A-Z_]+) +(@.*)");
    private static final Pattern RUN = Pattern.compile("@ *\\d+ (.+)");
    private static final Pattern MASKED_WORD = Pattern.compile("[x9 $,.\\-/*#:()&+'~]+");

    /**
     * Every line that isn't a row: fixed words, names and counts. Variable parts are numbers, marker
     * names, ✓ and ✗, parser ids and kinds; never statement text.
     */
    private static final List<Pattern> FIXED_LINES = Stream.of(
            "detect [a-z-]+: (DEFINITE|NONE) \\(markers:( [a-z]+ [✓✗])+\\)",
            "",
            "== summary ==",
            "pages: \\d+ \\(lines per page: \\d+(, \\d+)*\\)",
            "lines: \\d+",
            "dropped glyphs: rotated \\d+, unmapped \\d+",
            "lines per kind:",
            "  ([A-Z_]+) +\\d+",
            "parse: (not implemented|not run \\(no layout recognized\\)|not run \\(\\d+ layouts recognized\\))",
            "reconciliation: not run",
            "open layout guesses \\([a-z-]+\\):",
            "  - (.+)").map(Pattern::compile).toList();

    static Stream<String> fixtures() throws IOException {
        return Files.readAllLines(PDF.resolve("pdf-manifest.csv")).stream().skip(1).map(line -> line.split(",", -1)[0]);
    }

    static Stream<String> layoutFixtures() throws IOException {
        return manifestRows().filter(c -> c[1].equals("boa-checking")).map(c -> c[0]);
    }

    static Stream<String> unrecognizedFixtures() throws IOException {
        return manifestRows().filter(c -> c[1].equals("none")).map(c -> c[0]);
    }

    private static Stream<String[]> manifestRows() throws IOException {
        return Files.readAllLines(PDF.resolve("pdf-manifest.csv")).stream().skip(1).map(line -> line.split(",", -1));
    }

    private static List<String> skeleton(String fixture, boolean showLayoutWords) throws IOException {
        return SKELETON.render(Files.readAllBytes(PDF.resolve(fixture)), showLayoutWords);
    }

    private static List<String> rowKinds(List<String> output) {
        return output.stream().map(ROW::matcher).filter(Matcher::matches)
                .map(m -> "p" + m.group(1) + " L" + m.group(2) + " " + m.group(3)).toList();
    }

    // ------------------------------------------------------------------ kinds per fixture

    @ParameterizedTest(name = "{0}")
    @MethodSource("layoutFixtures")
    void recognizedFixturesShowTheParsersKindForEveryLine(String fixture) throws IOException {
        List<String> output = skeleton(fixture, false);

        assertThat(output.getFirst()).isEqualTo(
                "detect boa-checking: DEFINITE (markers: period ✓ beginning ✓ deposits ✓ withdrawals ✓ ending ✓)");
        assertThat(rowKinds(output)).containsExactlyElementsOf(
                Files.readAllLines(PDF.resolve(fixture.replace(".pdf", ".labels.txt"))));
        assertThat(output).contains("parse: not implemented", "open layout guesses (boa-checking):");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unrecognizedFixtures")
    void unrecognizedFixturesAreUnclassifiedWithEachMarkerReported(String fixture) throws IOException {
        List<String> output = skeleton(fixture, false);
        int lines = Files.readAllLines(PDF.resolve(fixture.replace(".pdf", ".lines.txt"))).size();

        assertThat(output.getFirst()).startsWith("detect boa-checking: NONE (markers: period ")
                .containsAnyOf("✗");
        assertThat(rowKinds(output)).hasSize(lines).allSatisfy(row -> assertThat(row).endsWith(" UNCLASSIFIED"));
        assertThat(output).contains("lines: " + lines, "  UNCLASSIFIED      " + lines,
                "parse: not run (no layout recognized)", "open layout guesses (boa-checking):");
    }

    @Test
    void theUnrecognizedLayoutShowsWhichMarkersAreMissing() throws IOException {
        assertThat(skeleton("edge-cases/pdf-unrecognized-layout.pdf", false).getFirst()).isEqualTo(
                "detect boa-checking: NONE (markers: period ✗ beginning ✗ deposits ✗ withdrawals ✗ ending ✗)");
    }

    // ------------------------------------------------------------------ the masking guarantee

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyFixtureIsFullyMasked(String fixture) throws IOException {
        assertOnlyMaskedOrFixed(skeleton(fixture, false), Set.of());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void showLayoutWordsUnmasksOnlyAllowListedWords(String fixture) throws IOException {
        List<String> output = skeleton(fixture, true);
        Set<String> unmasked = assertOnlyMaskedOrFixed(output, BOA.layoutWords());

        if (!fixture.endsWith("pdf-no-text.pdf")) {
            assertThat(unmasked).as("layout words shown").isNotEmpty();
        }
    }

    @Test
    void showLayoutWordsRevealsARenamedHeadingButNotItsNewWords() throws IOException {
        List<String> output = skeleton("northfield-checking_2026-04.pdf", true);

        assertThat(output).anySatisfy(line -> assertThat(line)
                .matches("p1 L0016 SECTION_START +@ 36 Deposits and other additions"));
        assertThat(output).anySatisfy(line -> assertThat(line)
                .matches("p2 L0039 SECTION_CONTINUED +@ 36 Withdrawals and other subtractions - continued"));
        // Descriptions stay masked: none of their words is layout vocabulary.
        assertThat(output).anySatisfy(line -> assertThat(line)
                .matches("p1 L0018 TXN +@ 36 99/99/99 \\| @ 90 [x ]+ \\| @541 9,999.99"));
    }

    /**
     * Asserts every output line is a row whose words are masked (or in {@code allowed}), or one of
     * the fixed lines, and returns the allowed words that appeared.
     */
    private static Set<String> assertOnlyMaskedOrFixed(List<String> output, Set<String> allowed) {
        Set<String> kinds = new HashSet<>(BOA.kinds());
        kinds.add(PdfSkeleton.UNCLASSIFIED);
        Set<String> guesses = Set.copyOf(BOA.openGuesses());
        Set<String> unmasked = new HashSet<>();
        for (String line : output) {
            Matcher row = ROW.matcher(line);
            if (row.matches()) {
                assertThat(kinds).as(line).contains(row.group(3));
                for (String run : row.group(4).split(" \\| ")) {
                    Matcher words = RUN.matcher(run);
                    assertThat(words.matches()).as("run in %s", line).isTrue();
                    for (String word : words.group(1).split(" ")) {
                        if (allowed.contains(word)) {
                            unmasked.add(word);
                        } else {
                            assertThat(word).as("word in %s", line).matches(MASKED_WORD);
                        }
                    }
                }
                continue;
            }
            Matcher fixed = FIXED_LINES.stream().map(p -> p.matcher(line)).filter(Matcher::matches).findFirst()
                    .orElseThrow(() -> new AssertionError("Not a row and not a fixed line: " + line));
            if (fixed.pattern().pattern().startsWith("  (")) {
                assertThat(kinds).as(line).contains(fixed.group(1));
            } else if (fixed.pattern().pattern().startsWith("  - ")) {
                assertThat(guesses).as(line).contains(fixed.group(1));
            }
        }
        return unmasked;
    }

    @Test
    void masksLettersAndDigitsAndKeepsStructuralPunctuation() {
        assertThat(PdfSkeleton.mask("Café")).isEqualTo("xxxx");
        assertThat(PdfSkeleton.mask("12/31/26")).isEqualTo("99/99/99");
        assertThat(PdfSkeleton.mask("-$1,234.56")).isEqualTo("-$9,999.99");
        assertThat(PdfSkeleton.mask("(12.00)")).isEqualTo("(99.99)");
        assertThat(PdfSkeleton.mask("#1236*:")).isEqualTo("#9999*:");
        assertThat(PdfSkeleton.mask("A&B+C's")).isEqualTo("x&x+x'x");
        assertThat(PdfSkeleton.mask("€|@\"٣")).isEqualTo("~~~~9");
    }

    @Test
    void aGapOfMoreThan12PointsStartsANewRun() {
        PdfLine line = new PdfLine(1, 1, 100, List.of(
                word("Ab", 36, 50), word("cd", 62, 70), word("12", 82.5f, 90), word("Total", 300, 320)));

        assertThat(PdfSkeleton.maskedLine(line, Set.of())).isEqualTo("@ 36 xx xx | @ 83 99 | @300 xxxxx");
        assertThat(PdfSkeleton.maskedLine(line, Set.of("Total"))).isEqualTo("@ 36 xx xx | @ 83 99 | @300 Total");
    }

    private static PdfWord word(String text, float x, float xEnd) {
        return new PdfWord(text, x, xEnd, 100, 10, false);
    }

    // ------------------------------------------------------------------ the summary

    @Test
    void theSummaryCountsEveryKindAndListsTheOpenGuesses() throws IOException {
        List<String> output = skeleton("northfield-checking_2026-04.pdf", false);
        List<String> summary = output.subList(output.indexOf("== summary =="), output.size());
        List<String> rows = rowKinds(output);

        assertThat(summary).contains("pages: 2 (lines per page: 36, 8)", "lines: " + rows.size(),
                "dropped glyphs: rotated 0, unmapped 0", "parse: not implemented", "reconciliation: not run",
                "detect boa-checking: DEFINITE (markers: period ✓ beginning ✓ deposits ✓ withdrawals ✓ ending ✓)");
        List<String> counted = new ArrayList<>();
        for (String kind : BOA.kinds()) {
            long count = rows.stream().filter(row -> row.endsWith(" " + kind)).count();
            counted.add(String.format("  %-17s %d", kind, count));
        }
        int kindsAt = summary.indexOf("lines per kind:") + 1;
        assertThat(summary.subList(kindsAt, kindsAt + counted.size())).containsExactlyElementsOf(counted);
        assertThat(summary.subList(summary.size() - BOA.openGuesses().size(), summary.size()))
                .containsExactlyElementsOf(BOA.openGuesses().stream().map(guess -> "  - " + guess).toList());
    }

    @Test
    void twoRecognizingLayoutsLeaveEveryLineUnclassified() throws IOException {
        PdfSkeletonLayout other = new StubLayout("other-layout");
        List<String> output = new PdfSkeleton(List.of(BOA, other))
                .render(Files.readAllBytes(PDF.resolve("northfield-checking_2026-04.pdf")), true);

        assertThat(rowKinds(output)).allSatisfy(row -> assertThat(row).endsWith(" UNCLASSIFIED"));
        assertThat(output).contains("detect other-layout: DEFINITE (markers: everything ✓)",
                "parse: not run (2 layouts recognized)", "open layout guesses (other-layout):");
        assertOnlyMaskedOrFixed(output, BOA.layoutWords());
    }

    /** A layout that recognizes everything and knows no words; classification is never asked for. */
    private record StubLayout(String parserId) implements PdfSkeletonLayout {

        @Override
        public SequencedMap<String, Boolean> markers(PdfDocumentText text) {
            SequencedMap<String, Boolean> markers = new LinkedHashMap<>();
            markers.put("everything", true);
            return markers;
        }

        @Override
        public List<String> kinds() {
            return List.of();
        }

        @Override
        public List<String> classify(PdfDocumentText text) {
            throw new AssertionError("an ambiguous file is not classified");
        }

        @Override
        public Set<String> layoutWords() {
            return Set.of();
        }

        @Override
        public List<String> openGuesses() {
            return List.of();
        }

        @Override
        public String parseStatus(PdfDocumentText text) {
            throw new AssertionError("an ambiguous file is not parsed");
        }
    }

    // ------------------------------------------------------------------ never shown

    @Test
    void metadataIsNeverShown() throws IOException {
        byte[] pdf;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                text(content, 72, 100, "Visible statement text");
            }
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle(MARKER);
            info.setAuthor(MARKER);
            info.setSubject(MARKER);
            info.setKeywords(MARKER);
            document.setDocumentInformation(info);
            pdf = save(document);
        }

        List<String> output = SKELETON.render(pdf, true);

        assertThat(String.join("\n", output)).doesNotContain(MARKER).doesNotContain("Visible");
        assertThat(rowKinds(output)).containsExactly("p1 L0001 UNCLASSIFIED");
        assertThat(output).contains("p1 L0001 UNCLASSIFIED      @ 72 xxxxxxx xxxxxxxxx xxxx");
    }

    // ------------------------------------------------------------------ the command line

    @Nested
    class CommandLine {

        @TempDir
        Path dir;

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        private String output() {
            return bytes.toString(StandardCharsets.UTF_8);
        }

        @Test
        void printsTheSkeletonOfAFixture() throws IOException {
            Path fixture = PDF.resolve("northfield-checking_2026-04.pdf");

            int status = PdfSkeletonCli.run(new String[] {fixture.toString()}, out);

            assertThat(status).isZero();
            assertThat(output()).isEqualTo(String.join(System.lineSeparator(),
                    SKELETON.render(Files.readAllBytes(fixture), false)) + System.lineSeparator());
        }

        @Test
        void takesTheFlagBeforeOrAfterThePath() throws IOException {
            Path copy = Files.copy(PDF.resolve("northfield-checking_2026-04.pdf"), dir.resolve("a statement.pdf"));

            assertThat(PdfSkeletonCli.run(new String[] {"--show-layout-words", copy.toString()}, out)).isZero();
            String before = output();
            bytes.reset();
            assertThat(PdfSkeletonCli.run(new String[] {copy.toString(), "--show-layout-words"}, out)).isZero();

            assertThat(output()).isEqualTo(before).contains("Deposits and other additions");
        }

        @Test
        void usageErrors() {
            for (String[] args : List.of(new String[0], new String[] {"a.pdf", "b.pdf"}, new String[] {"--other"},
                    new String[] {"a.pdf", "--show-layout-words", "--show-layout-words"})) {
                bytes.reset();
                assertThat(PdfSkeletonCli.run(args, out)).as(String.join(" ", args)).isEqualTo(2);
                assertThat(output()).isEqualTo(PdfSkeletonCli.USAGE + System.lineSeparator());
            }
        }

        @Test
        void unreadableInputNamesNeitherTheFileNorTheError() throws IOException {
            Path missing = dir.resolve("SECRETNAME.pdf");
            Path text = Files.writeString(dir.resolve("SECRETNAME.csv"), "Date,Amount\n" + MARKER + ",1.00\n");
            Path large = dir.resolve("SECRETNAME-large.pdf");
            try (RandomAccessFile file = new RandomAccessFile(large.toFile(), "rw")) {
                file.setLength(PdfSkeletonCli.MAX_BYTES + 1);
            }
            Path broken = Files.write(dir.resolve("SECRETNAME-broken.pdf"),
                    ("%PDF-1.7\n" + MARKER).getBytes(StandardCharsets.US_ASCII));

            assertFails(missing, "read: FAILED (not a file)");
            assertFails(dir, "read: FAILED (not a file)");
            assertFails(text, "read: FAILED (not a PDF: no %PDF- header)");
            assertFails(large, "read: FAILED (larger than 10 MB, the upload limit)");
            assertFails(broken, "extract: FAILED (unreadable)");
        }

        @Test
        void aPasswordProtectedPdfIsReportedAsSuch() throws IOException {
            Path encrypted;
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    text(content, 72, 100, MARKER);
                }
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "user", new AccessPermission());
                policy.setEncryptionKeyLength(256);
                document.protect(policy);
                encrypted = Files.write(dir.resolve("SECRETNAME.pdf"), save(document));
            }

            assertFails(encrypted, "extract: FAILED (password required)");
        }

        private void assertFails(Path path, String expected) {
            bytes.reset();

            assertThat(PdfSkeletonCli.run(new String[] {path.toString()}, out)).as(expected).isEqualTo(1);
            assertThat(output()).isEqualTo(expected + System.lineSeparator())
                    .doesNotContain("SECRETNAME").doesNotContain(MARKER);
        }
    }
}
