package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.support.ImportTestSupport;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The CAT-05 dev and holdout sets (docs/plans/phase-4.md §10, step 1b), written by
 * {@code tools/SampleDataGenerator.java}; {@code SampleDataTest} checks that regenerating them is
 * byte-identical, and {@code CategorySeedTest} that their categories are the seeded ones.
 *
 * <p>The holdout is frozen and only reported on. Its SHA-256 is pinned here and in the plan, so any
 * edit fails the build and shows up in review. Seed rules, cleaning patterns and prompts are tuned
 * against the answer key and the dev set only; the last test keeps every other class from reading
 * the holdout.
 */
class CategorizationSetsTest {

    static final Path DEV = ImportTestSupport.SAMPLE_DATA.resolve("categorization/dev.csv");
    static final Path HOLDOUT = ImportTestSupport.SAMPLE_DATA.resolve("categorization/holdout.csv");

    /** Recorded in docs/plans/phase-4.md (step 1b notes). Never update it to make a change pass. */
    private static final String HOLDOUT_SHA_256 = "2020780ea41e1c1916247250e5eaab847ab97a523635dae9a070ab6f6cee92af";
    private static final int HOLDOUT_ROWS = 150;

    /**
     * The only classes allowed to name the holdout: this test, the category check, the seed leakage check (names
     * only, step 2) and the CAT-05 reports (step 9).
     */
    private static final Set<String> HOLDOUT_READERS = Set.of("CategorizationSetsTest.java", "CategorySeedTest.java",
            "SeedRulesTest.java", "CategorizationAccuracyTest.java", "CategorizationLiveEvalIT.java");

    private static final Pattern WORD = Pattern.compile("[A-Z]{3,}");
    private static final Pattern MONEY = Pattern.compile("-?\\d+\\.\\d{2}");

    record Row(String description, String amount, String expectedCategory, String name) {}

    @Test
    void theHoldoutIsFrozen() throws IOException, NoSuchAlgorithmException {
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(HOLDOUT)));

        assertThat(sha256)
                .as("holdout.csv changed. It is frozen (docs/plans/phase-4.md §10): never edit it, and never tune "
                        + "seed rules, cleaning patterns or prompts against it. Revert the change.")
                .isEqualTo(HOLDOUT_SHA_256);
    }

    @Test
    void theHoldoutSaysItIsFrozen() throws IOException {
        List<String> comments = Files.readAllLines(HOLDOUT).stream().takeWhile(line -> line.startsWith("#")).toList();

        assertThat(String.join("\n", comments)).contains("FROZEN: reporting only").contains("dev.csv");
    }

    @Test
    void bothSetsAreWellFormed() throws IOException {
        assertThat(rows(HOLDOUT)).hasSize(HOLDOUT_ROWS);
        assertThat(rows(DEV)).hasSizeGreaterThanOrEqualTo(100);
        for (Path set : List.of(DEV, HOLDOUT)) {
            assertThat(header(set)).as("%s header", set).isEqualTo("description,amount,expected_category,name");
            for (Row row : rows(set)) {
                assertThat(row.description()).as("description in %s", set).isNotBlank().doesNotStartWith("#");
                assertThat(row.amount()).as("amount of %s", row.description()).matches(MONEY);
                assertThat(new BigDecimal(row.amount()).signum()).as("amount of %s", row.description()).isNotZero();
                assertThat(row.expectedCategory()).as("category of %s", row.description()).isNotBlank();
            }
        }
    }

    @Test
    void everyNameAppearsInItsDescription() throws IOException {
        for (Path set : List.of(DEV, HOLDOUT)) {
            for (Row row : rows(set)) {
                String description = row.description().toUpperCase(Locale.ROOT).replace(" ", "");
                assertThat(description).as("%s in %s", row.name(), set).contains(row.name().replace(" ", ""));
            }
        }
    }

    /** A rule written for a dev merchant can't match a holdout merchant by name. */
    @Test
    void theNamePoolsShareNoWord() throws IOException {
        Set<String> shared = nameWords(DEV);
        shared.retainAll(nameWords(HOLDOUT));

        assertThat(nameWords(DEV)).isNotEmpty();
        assertThat(shared).as("words in both dev and holdout names").isEmpty();
    }

    /** Rules may be tuned on the answer key and the merchant fixture too, so their words stay out of holdout names. */
    @Test
    void holdoutNamesShareNoWordWithTheTuningData() throws IOException {
        Set<String> tuning = new TreeSet<>();
        for (Path file : List.of(ImportTestSupport.SAMPLE_DATA.resolve("answer-key.csv"),
                ImportTestSupport.SAMPLE_DATA.resolve("edge-cases/merchants/merchants-expected.csv"))) {
            tuning.addAll(words(Files.readString(file)));
        }
        Set<String> shared = nameWords(HOLDOUT);
        shared.retainAll(tuning);

        assertThat(shared).as("holdout name words found in the answer key or merchant fixture").isEmpty();
    }

    /**
     * The AI tests' fixtures (steps 7 and 8) use businesses of their own: their descriptions share no word with the
     * holdout names. Reads the description column only.
     */
    @Test
    void holdoutNamesShareNoWordWithTheAiFixtures() throws IOException {
        Set<String> fixtures = new TreeSet<>();
        Path dir = ImportTestSupport.SAMPLE_DATA.resolve("edge-cases/ai-categorization");
        for (Path file : List.of(dir.resolve("ai-categorization-cases.csv"),
                dir.resolve("similar-merchant-cases.csv"))) {
            Files.readAllLines(file).stream().skip(1).forEach(line -> fixtures.addAll(words(line.split(",", -1)[2])));
        }
        Set<String> shared = nameWords(HOLDOUT);
        shared.retainAll(fixtures);

        assertThat(fixtures).contains("QUORVANE", "BRAMBLEWICK");
        assertThat(shared).as("holdout name words found in the AI fixtures").isEmpty();
    }

    /** Main code never names the holdout, and only the reporting tests read it. */
    @Test
    void onlyTheReportsReadTheHoldout() throws IOException {
        assertThat(filesMentioningHoldout(Path.of("src/main/java"))).isEmpty();
        assertThat(filesMentioningHoldout(Path.of("src/main/resources"))).isEmpty();
        assertThat(filesMentioningHoldout(Path.of("src/test")))
                .allMatch(file -> HOLDOUT_READERS.contains(file.getFileName().toString()));
    }

    /** Files with a non-comment line that mentions the holdout; comments may say "never tune against the holdout". */
    private static List<Path> filesMentioningHoldout(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> {
                        try {
                            return Files.readAllLines(file).stream()
                                    .map(String::strip)
                                    .filter(line -> !line.startsWith("#") && !line.startsWith("--")
                                            && !line.startsWith("//") && !line.startsWith("*") && !line.startsWith("/*"))
                                    .anyMatch(line -> line.toLowerCase(Locale.ROOT).contains("holdout"));
                        } catch (IOException e) {
                            return false;   // binary resources
                        }
                    })
                    .toList();
        }
    }

    private static Set<String> nameWords(Path set) throws IOException {
        Set<String> words = new TreeSet<>();
        for (Row row : rows(set)) {
            words.addAll(words(row.name()));
        }
        return words;
    }

    private static Set<String> words(String text) {
        Set<String> words = new TreeSet<>();
        Matcher m = WORD.matcher(text.toUpperCase(Locale.ROOT));
        while (m.find()) {
            words.add(m.group());
        }
        return words;
    }

    private static String header(Path set) throws IOException {
        return Files.readAllLines(set).stream().filter(line -> !line.startsWith("#")).findFirst().orElseThrow();
    }

    /** Rows after the comment lines and the header; the generator guarantees no cell contains a comma or quote. */
    static List<Row> rows(Path set) throws IOException {
        return Files.readAllLines(set).stream()
                .filter(line -> !line.startsWith("#"))
                .skip(1)
                .map(line -> line.split(",", -1))
                .map(c -> new Row(c[0], c[1], c[2], c[3]))
                .toList();
    }
}
