package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.categorize.RuleEngine.Rule;
import com.finalysis.categorize.RuleEngine.Subject;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The seeded rules of V15 (CAT-01; docs/plans/phase-4.md §3.3, §10). Tuned against the answer key, the
 * merchant edge-case fixture and the dev set, where they must never give a wrong category (leaving a
 * row uncategorized is fine: the AI fills gaps). The frozen set is never scored here; its names are read
 * only to check that no seed was written for one of them.
 */
@IntegrationTest
class SeedRulesTest {

    private static final Path ANSWER_KEY = ImportTestSupport.SAMPLE_DATA.resolve("answer-key.csv");
    private static final Path FIXTURE = ImportTestSupport.SAMPLE_DATA.resolve("edge-cases/merchants/merchants-expected.csv");
    private static final Pattern WORD = Pattern.compile("[A-Z]{3,}");

    /**
     * Fictional names in the answer key (it has no name column); seeds must not be written for them. THAI in
     * {@code DOORDASH*THAI BASIL} is a cuisine word and a generic seed, as dev.csv's name column treats it.
     */
    private static final Set<String> ANSWER_KEY_NAMES = Set.of("BLUE BEAN", "NAKAMURA", "PARKSIDE", "BRIGHTWAY",
            "SKYWAVE", "CLEARLINE", "METRO", "APEX", "SUMMIT", "NORTHFIELD", "BASIL");

    /**
     * What the seeds should give each fixture row, written by hand (null: rules leave it to the AI). The last
     * row is Health, but its merchant is cut at 64 characters before THERAPY.
     */
    private static final Map<String, String> FIXTURE_EXPECTED = Map.ofEntries(
            Map.entry("HARBORLINE FREIGHT PAYROLL PPD ID: 7700112299", "Income"),
            Map.entry("ZELLE PAYMENT TO JANE Q SAMPLE CONF# 8XK2P1", "Person-to-Person Out"),
            Map.entry("ZELLE PAYMENT FROM ALEX R EXAMPLE CONF# 3MZ8Q0", "Person-to-Person In"),
            Map.entry("ACH DEBIT ACME INS CO ID:9900112233 INDN:SAMPLE JANE", ""),
            Map.entry("CHECKCARD 0504 NORTHGATE FUEL #88 SAMPLETOWN VA", "Transportation"),
            Map.entry("POS DEBIT SQ *CORNER BEAN CAFE 0412", "Dining"),
            Map.entry("MAPLEWOOD GROCERY\n#214 SAMPLETOWN VA", "Groceries"),
            Map.entry("ONLINE TRANSFER TO SAVINGS XXXXXX1002", "Transfer"),
            Map.entry("CAFÉ LUMIÈRE", "Dining"),
            Map.entry("=HYPERLINK(\"x\")", ""),
            Map.entry("#12345 0099", ""),
            Map.entry("VENMO PAYMENT 1023456789 WEB ID: 3264681992", "Person-to-Person Out"),
            Map.entry("CASH APP*JANE SAMPLE", "Person-to-Person Out"),
            Map.entry("SUNRISE COMMUNITY HEALTH AND WELLNESS CENTER OUTPATIENT PHYSICAL THERAPY SERVICES", ""));

    private final CategorizationService categorization;
    private final MerchantCleaner cleaner;
    private final JdbcTemplate jdbc;

    private List<Rule> seeds;
    private RuleEngine engine;
    private Map<Long, String> categoryNames;
    private Map<Long, String> categoryKinds;

    SeedRulesTest(CategorizationService categorization, MerchantCleaner cleaner, JdbcTemplate jdbc) {
        this.categorization = categorization;
        this.cleaner = cleaner;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void loadSeeds() {
        seeds = categorization.activeRules().stream().filter(rule -> rule.source() == RuleSource.SEED).toList();
        engine = new RuleEngine(seeds);
        categoryNames = new TreeMap<>();
        categoryKinds = new TreeMap<>();
        jdbc.query("SELECT id, name, kind FROM category", rs -> {
            categoryNames.put(rs.getLong("id"), rs.getString("name"));
            categoryKinds.put(rs.getLong("id"), rs.getString("kind"));
        });
    }

    private record Row(String description, BigDecimal amount, String expected) {}

    private String answer(String description, BigDecimal amount) {
        String merchant = cleaner.clean(description).merchant();
        return engine.winner(Subject.of(merchant, description, amount))
                .map(rule -> categoryNames.get(rule.categoryId()))
                .orElse(null);
    }

    /** Rows where the rules give a category other than the expected one. */
    private List<String> wrong(List<Row> rows) {
        List<String> wrong = new ArrayList<>();
        for (Row row : rows) {
            String answer = answer(row.description(), row.amount());
            if (answer != null && !answer.equals(row.expected())) {
                wrong.add(row.description() + " " + row.amount() + ": " + answer + ", expected " + row.expected());
            }
        }
        return wrong;
    }

    @Test
    void seedsLoadAndCompile() {
        assertThat(seeds).hasSizeGreaterThan(60);
        assertThat(engine.rules()).as("every seed regex compiles").hasSameSizeAs(seeds);
    }

    @Test
    void noWrongAnswerOnTheAnswerKey() throws IOException {
        List<Row> rows = answerKey();
        assertThat(rows).hasSizeGreaterThan(200);

        assertThat(wrong(rows)).isEmpty();
    }

    @Test
    void noWrongAnswerOnTheDevSet() throws IOException {
        List<Row> rows = CategorizationSetsTest.rows(CategorizationSetsTest.DEV).stream()
                .map(row -> new Row(row.description(), new BigDecimal(row.amount()), row.expectedCategory()))
                .toList();

        assertThat(wrong(rows)).isEmpty();
        report(rows);
    }

    @Test
    void theFixtureGetsTheHandWrittenAnswers() throws IOException {
        Map<String, String> actual = new TreeMap<>();
        for (CSVRecord record : csv(FIXTURE)) {
            String answer = answer(record.get("description"), new BigDecimal(record.get("amount")));
            actual.put(record.get("description"), answer == null ? "" : answer);
        }

        assertThat(actual).isEqualTo(new TreeMap<>(FIXTURE_EXPECTED));
    }

    /** Expense seeds match money out only, income seeds money in only, transfer seeds either way. */
    @Test
    void seedDirectionsFollowTheCategoryKind() {
        for (Rule seed : seeds) {
            RuleDirection expected = switch (categoryKinds.get(seed.categoryId())) {
                case "EXPENSE" -> RuleDirection.OUT;
                case "INCOME" -> RuleDirection.IN;
                default -> RuleDirection.ANY;
            };
            assertThat(seed.direction()).as("seed %d (%s)", seed.id(), seed.pattern()).isEqualTo(expected);
        }
    }

    /** §3.3: never the sample data's fictional names, so the eval isn't rigged. */
    @Test
    void noSeedNamesASampleMerchant() throws IOException {
        Set<String> names = new TreeSet<>(words(String.join(" ", ANSWER_KEY_NAMES)));
        for (CategorizationSetsTest.Row row : CategorizationSetsTest.rows(CategorizationSetsTest.DEV)) {
            names.addAll(words(row.name()));
        }

        assertThat(seedWords()).doesNotContainAnyElementsOf(names);
    }

    /**
     * Leakage guard (§10): no seed pattern shares a word with a frozen-set merchant name, and no seed matches
     * one of those names in either field or direction. Reads the name column only.
     */
    @Test
    void noSeedMatchesAHoldoutName() throws IOException {
        List<String> holdoutNames = CategorizationSetsTest.rows(CategorizationSetsTest.HOLDOUT).stream()
                .map(CategorizationSetsTest.Row::name)
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
        assertThat(holdoutNames).hasSizeGreaterThan(10);

        Set<String> nameWords = new TreeSet<>();
        holdoutNames.forEach(name -> nameWords.addAll(words(name)));
        assertThat(seedWords()).as("seed pattern words in frozen-set names").doesNotContainAnyElementsOf(nameWords);

        List<String> matched = new ArrayList<>();
        for (String name : holdoutNames) {
            for (BigDecimal amount : List.of(new BigDecimal("-1.00"), new BigDecimal("1.00"))) {
                engine.winner(Subject.of(name, name, amount)).ifPresent(rule -> matched.add(name + " by seed " + rule.id()));
            }
        }
        assertThat(matched).as("frozen-set names matched by a seed").isEmpty();
    }

    /** Prints the dev set's rules-only result (no assertion: the coverage floor is set in step 9). */
    private void report(List<Row> rows) {
        int correct = 0;
        Map<String, int[]> byCategory = new TreeMap<>();
        for (Row row : rows) {
            String answer = answer(row.description(), row.amount());
            int[] counts = byCategory.computeIfAbsent(row.expected(), category -> new int[2]);
            counts[0]++;
            if (row.expected().equals(answer)) {
                correct++;
                counts[1]++;
            }
        }
        String perCategory = byCategory.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()[1] + "/" + e.getValue()[0])
                .collect(Collectors.joining(", "));
        System.out.printf("Dev set, rules only: %d of %d correct, 0 wrong, %d uncategorized. Per category: %s%n",
                correct, rows.size(), rows.size() - correct, perCategory);
    }

    private Set<String> seedWords() {
        Set<String> words = new TreeSet<>();
        seeds.forEach(seed -> words.addAll(words(seed.pattern())));
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

    private static List<Row> answerKey() throws IOException {
        return csv(ANSWER_KEY).stream()
                .map(r -> new Row(r.get("description"), new BigDecimal(r.get("amount")), r.get("expected_category")))
                .toList();
    }

    private static List<CSVRecord> csv(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader).getRecords();
        }
    }
}
