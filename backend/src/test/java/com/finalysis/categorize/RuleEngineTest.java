package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.categorize.RuleEngine.Rule;
import com.finalysis.categorize.RuleEngine.Subject;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Matching and the winning order of docs/plans/phase-4.md §3.2, without a database. */
class RuleEngineTest {

    private static final BigDecimal OUT = new BigDecimal("-12.50");
    private static final BigDecimal IN = new BigDecimal("12.50");

    private long nextId = 1;

    private Rule rule(RuleField field, RuleMatchType type, String pattern) {
        return rule(field, type, pattern, RuleDirection.ANY, 0, RuleSource.SEED);
    }

    private Rule rule(RuleField field, RuleMatchType type, String pattern, RuleDirection direction, int priority,
                      RuleSource source) {
        long id = nextId++;
        return new Rule(id, field, type, pattern, direction, priority, source, 100 + id);
    }

    private static Subject merchant(String merchant) {
        return Subject.of(merchant, "RAW " + merchant, OUT);
    }

    private static boolean matches(Rule rule, Subject subject) {
        return new RuleEngine(List.of(rule)).winner(subject).isPresent();
    }

    // ------------------------------------------------------------------ match types

    @ParameterizedTest(name = "{0} {1} on \"{2}\" -> {3}")
    @CsvSource(delimiter = '|', textBlock = """
            EXACT    | BLUE BEAN CAFE | BLUE BEAN CAFE        | true
            EXACT    | BLUE BEAN      | BLUE BEAN CAFE        | false
            EXACT    | BLUE BEAN CAFE | BLUE BEAN CAFE ANNEX  | false
            PREFIX   | BLUE BEAN      | BLUE BEAN CAFE        | true
            PREFIX   | BLUE BEAN CAFE | BLUE BEAN CAFE        | true
            PREFIX   | BLUE BE        | BLUE BEAN CAFE        | false
            PREFIX   | BEAN CAFE      | BLUE BEAN CAFE        | false
            CONTAINS | BEAN           | BLUE BEAN CAFE        | true
            CONTAINS | CAFE           | BLUE BEAN CAFE        | true
            CONTAINS | BLUE           | BLUE BEAN CAFE        | true
            CONTAINS | EAN            | BLUE BEAN CAFE        | false
            CONTAINS | SHELL          | SHELL OIL             | true
            CONTAINS | SHELL          | SHELLFISH SHACK       | false
            CONTAINS | SHELL          | SEASHELL GIFTS        | false
            CONTAINS | ELECTRIC       | VOLTARA ELECTRONICS   | false
            CONTAINS | ELECTRIC       | NORTHRIDGE ELECTRIC   | true
            CONTAINS | NETFLIX        | NETFLIX.COM           | true
            CONTAINS | CVS            | CVS/PHARMACY          | true
            CONTAINS | TRADER JOE     | TRADER JOE S          | true
            CONTAINS | CAFE           | CAFETERIA CAFE        | true
            CONTAINS | 7-ELEVEN       | 7-ELEVEN              | true
            REGEX    | ^BLUE\\b       | BLUE BEAN CAFE        | true
            REGEX    | bean           | BLUE BEAN CAFE        | true
            REGEX    | CAFE$          | BLUE BEAN CAFE ANNEX  | false
            REGEX    | \\bEAN         | BLUE BEAN CAFE        | false
            """)
    void matchesEachTypeOnWholeWords(RuleMatchType type, String pattern, String merchant, boolean expected) {
        assertThat(matches(rule(RuleField.MERCHANT, type, pattern), merchant(merchant))).isEqualTo(expected);
    }

    @Test
    void aPatternEdgedWithPunctuationNeedsNoBoundaryOnThatSide() {
        // The '.' is not a letter or digit, so the text after it may start right away.
        assertThat(matches(rule(RuleField.MERCHANT, RuleMatchType.PREFIX, "NETFLIX."), merchant("NETFLIX.COM"))).isTrue();
        assertThat(matches(rule(RuleField.MERCHANT, RuleMatchType.CONTAINS, "/PHARMACY"), merchant("CVS/PHARMACY"))).isTrue();
    }

    @Test
    void aLaterOccurrenceCanBeTheWholeWord() {
        assertThat(matches(rule(RuleField.MERCHANT, RuleMatchType.CONTAINS, "CAFE"), merchant("CAFES AND CAFE"))).isTrue();
    }

    // ------------------------------------------------------------------ fields

    @Test
    void merchantRulesMatchTheMerchantAndDescriptionRulesTheNormalizedRawDescription() {
        Subject subject = Subject.of("BRIGHTWAY LOGISTICS PAYROLL", "Brightway Logistics\nPAYROLL  PPD ID: 4419920113", IN);

        assertThat(matches(rule(RuleField.DESCRIPTION, RuleMatchType.CONTAINS, "PPD ID"), subject))
                .as("the description keeps what cleaning removes").isTrue();
        assertThat(matches(rule(RuleField.MERCHANT, RuleMatchType.CONTAINS, "PPD ID"), subject)).isFalse();
        assertThat(matches(rule(RuleField.DESCRIPTION, RuleMatchType.CONTAINS, "LOGISTICS PAYROLL"), subject))
                .as("wrapped lines joined, upper case, single spaces").isTrue();
        assertThat(subject.description()).isEqualTo("BRIGHTWAY LOGISTICS PAYROLL PPD ID: 4419920113");
    }

    @Test
    void aTxnWithoutAMerchantMatchesNoMerchantRule() {
        Subject subject = Subject.of(null, "#12345 0099", OUT);

        assertThat(matches(rule(RuleField.MERCHANT, RuleMatchType.REGEX, ".*"), subject)).isFalse();
        assertThat(matches(rule(RuleField.DESCRIPTION, RuleMatchType.REGEX, ".*"), subject)).isTrue();
    }

    @Test
    void inputsAreCutTo256Characters() {
        String longText = "A ".repeat(200) + "TAIL";
        Subject subject = Subject.of(longText, longText, OUT);

        assertThat(subject.merchant()).hasSize(RuleEngine.MAX_INPUT);
        assertThat(subject.description()).hasSize(RuleEngine.MAX_INPUT);
        assertThat(matches(rule(RuleField.DESCRIPTION, RuleMatchType.CONTAINS, "TAIL"), subject)).isFalse();
    }

    // ------------------------------------------------------------------ direction

    @ParameterizedTest(name = "{0} on {1} -> {2}")
    @CsvSource({
            "ANY, -12.50, true", "ANY, 12.50, true", "ANY, 0.00, true",
            "OUT, -12.50, true", "OUT, 12.50, false", "OUT, 0.00, false",
            "IN,  -12.50, false", "IN, 12.50, true", "IN, 0.00, false"})
    void directionFollowsTheSign(RuleDirection direction, BigDecimal amount, boolean expected) {
        Rule rule = rule(RuleField.MERCHANT, RuleMatchType.EXACT, "ZELLE", direction, 0, RuleSource.SEED);

        assertThat(matches(rule, Subject.of("ZELLE", "ZELLE", amount))).isEqualTo(expected);
    }

    @Test
    void oneMerchantGoesToDifferentCategoriesByDirection() {
        Rule out = rule(RuleField.MERCHANT, RuleMatchType.EXACT, "ZELLE", RuleDirection.OUT, 100, RuleSource.SEED);
        Rule in = rule(RuleField.MERCHANT, RuleMatchType.EXACT, "ZELLE", RuleDirection.IN, 100, RuleSource.SEED);
        RuleEngine engine = new RuleEngine(List.of(out, in));

        assertThat(engine.winner(Subject.of("ZELLE", "ZELLE PAYMENT TO X", OUT))).contains(out);
        assertThat(engine.winner(Subject.of("ZELLE", "ZELLE PAYMENT FROM X", IN))).contains(in);
    }

    // ------------------------------------------------------------------ winning order

    /** Two rules that both match "BLUE BEAN CAFE"; {@code better} must win, in either input order. */
    private void assertWins(Rule better, Rule worse) {
        Subject subject = merchant("BLUE BEAN CAFE");
        assertThat(new RuleEngine(List.of(better, worse)).winner(subject)).contains(better);
        assertThat(new RuleEngine(List.of(worse, better)).winner(subject)).contains(better);
        assertThat(RuleEngine.ORDER.compare(better, worse)).isNegative();
        assertThat(RuleEngine.ORDER.compare(worse, better)).isPositive();
    }

    @Test
    void userRulesOutrankSeedsWhateverThePriority() {
        for (RuleSource user : List.of(RuleSource.USER, RuleSource.USER_CORRECTION)) {
            Rule mine = rule(RuleField.MERCHANT, RuleMatchType.REGEX, "B", RuleDirection.ANY, 0, user);
            Rule seed = rule(RuleField.MERCHANT, RuleMatchType.EXACT, "BLUE BEAN CAFE", RuleDirection.ANY, 1000,
                    RuleSource.SEED);
            assertWins(mine, seed);
        }
    }

    @Test
    void bothUserSourcesRankTogetherAndTheRestDecides() {
        Rule correction = rule(RuleField.MERCHANT, RuleMatchType.CONTAINS, "CAFE", RuleDirection.ANY, 5,
                RuleSource.USER_CORRECTION);
        Rule user = rule(RuleField.MERCHANT, RuleMatchType.CONTAINS, "CAFE", RuleDirection.ANY, 6, RuleSource.USER);
        assertWins(user, correction);
    }

    @Test
    void thenHigherPriority() {
        assertWins(rule(RuleField.MERCHANT, RuleMatchType.REGEX, "B", RuleDirection.ANY, 10, RuleSource.SEED),
                rule(RuleField.MERCHANT, RuleMatchType.EXACT, "BLUE BEAN CAFE", RuleDirection.ANY, 9, RuleSource.SEED));
    }

    @Test
    void thenTheMoreSpecificMatchType() {
        Rule exact = rule(RuleField.MERCHANT, RuleMatchType.EXACT, "BLUE BEAN CAFE");
        Rule prefix = rule(RuleField.MERCHANT, RuleMatchType.PREFIX, "BLUE");
        Rule contains = rule(RuleField.MERCHANT, RuleMatchType.CONTAINS, "BEAN");
        Rule regex = rule(RuleField.MERCHANT, RuleMatchType.REGEX, "BLUE BEAN CAFE AND LONGER|BLUE");
        assertWins(exact, prefix);
        assertWins(prefix, contains);
        assertWins(contains, regex);
        assertWins(exact, regex);
    }

    @Test
    void thenTheLongerPattern() {
        assertWins(rule(RuleField.MERCHANT, RuleMatchType.CONTAINS, "BLUE BEAN"),
                rule(RuleField.MERCHANT, RuleMatchType.CONTAINS, "CAFE"));
    }

    @Test
    void thenTheLowerId() {
        Rule first = rule(RuleField.MERCHANT, RuleMatchType.CONTAINS, "BLUE");
        Rule second = rule(RuleField.MERCHANT, RuleMatchType.CONTAINS, "CAFE");
        assertWins(first, second);
    }

    /** The field and direction never break a tie; neither does the category. */
    @Test
    void fieldDirectionAndCategoryDontRank() {
        Rule merchant = new Rule(1, RuleField.MERCHANT, RuleMatchType.CONTAINS, "CAFE", RuleDirection.OUT, 0,
                RuleSource.SEED, 900);
        Rule description = new Rule(2, RuleField.DESCRIPTION, RuleMatchType.CONTAINS, "CAFE", RuleDirection.ANY, 0,
                RuleSource.SEED, 1);
        assertWins(merchant, description);
    }

    /** Every pair of a grid of rules is ordered as the five criteria say, and the order is total. */
    @Test
    void theOrderIsTheFiveCriteriaInTurn() {
        List<Rule> rules = new ArrayList<>();
        long id = 1;
        for (RuleSource source : RuleSource.values()) {
            for (int priority : new int[] {0, 50}) {
                for (RuleMatchType type : RuleMatchType.values()) {
                    for (String pattern : new String[] {"AB", "ABC"}) {
                        rules.add(new Rule(id++, RuleField.MERCHANT, type, pattern, RuleDirection.ANY, priority, source, 1));
                    }
                }
            }
        }
        List<Rule> shuffled = new ArrayList<>(rules);
        Collections.shuffle(shuffled, new Random(7));
        shuffled.sort(RuleEngine.ORDER);

        for (int i = 0; i + 1 < shuffled.size(); i++) {
            Rule a = shuffled.get(i);
            Rule b = shuffled.get(i + 1);
            assertThat(rank(a)).as("%s before %s", a, b).isLessThan(rank(b));
        }
    }

    /** The criteria as one sortable key, written independently of the comparator. */
    private static String rank(Rule rule) {
        return "%d|%04d|%d|%04d|%010d".formatted(rule.source() == RuleSource.SEED ? 1 : 0, 1000 - rule.priority(),
                rule.matchType().ordinal(), 200 - rule.pattern().length(), rule.id());
    }

    @Test
    void noMatchIsEmpty() {
        RuleEngine engine = new RuleEngine(List.of(rule(RuleField.MERCHANT, RuleMatchType.CONTAINS, "SHELL")));

        assertThat(engine.winner(merchant("BLUE BEAN CAFE"))).isEqualTo(Optional.empty());
    }

    @Test
    void theEngineListsItsRulesInWinningOrder() {
        Rule low = rule(RuleField.MERCHANT, RuleMatchType.CONTAINS, "CAFE");
        Rule high = rule(RuleField.MERCHANT, RuleMatchType.CONTAINS, "CAFE", RuleDirection.ANY, 9, RuleSource.SEED);

        assertThat(new RuleEngine(List.of(low, high)).rules()).containsExactly(high, low);
    }

    @Test
    void matchesAsksAboutOneRuleWhateverItsRank() {
        Rule high = rule(RuleField.MERCHANT, RuleMatchType.CONTAINS, "CAFE", RuleDirection.ANY, 9, RuleSource.SEED);
        Rule low = rule(RuleField.MERCHANT, RuleMatchType.CONTAINS, "BEAN");
        RuleEngine engine = new RuleEngine(List.of(high, low));

        assertThat(engine.matches(low.id(), merchant("BLUE BEAN CAFE"))).isTrue();
        assertThat(engine.matches(low.id(), merchant("CORNER CAFE"))).isFalse();
        assertThat(engine.matches(999, merchant("BLUE BEAN CAFE"))).as("not one of its rules").isFalse();
    }

    // ------------------------------------------------------------------ regex safety

    @Test
    @Timeout(5)
    void catastrophicBacktrackingEndsAtTheDeadline() {
        // Outranks the fallback, so it runs first; without the deadline it would take hours on this input.
        Rule evil = rule(RuleField.DESCRIPTION, RuleMatchType.REGEX, "(.*A){12}B", RuleDirection.ANY, 10, RuleSource.SEED);
        Rule fine = rule(RuleField.DESCRIPTION, RuleMatchType.CONTAINS, "END");
        RuleEngine engine = new RuleEngine(List.of(evil, fine), Duration.ofMillis(50));
        Subject subject = Subject.of(null, "A".repeat(60) + "C END", OUT);

        long start = System.nanoTime();
        Optional<Rule> winner = engine.winner(subject);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(winner).as("the timed-out regex is skipped and the next rule decides").contains(fine);
        assertThat(took).isLessThan(Duration.ofSeconds(2));
        assertThat(engine.timedOutRules()).containsExactly(evil.id());
    }

    @Test
    void anInvalidStoredRegexIsSkippedAndReported() {
        Rule broken = rule(RuleField.MERCHANT, RuleMatchType.REGEX, "(UNCLOSED");
        Rule fine = rule(RuleField.MERCHANT, RuleMatchType.CONTAINS, "UNCLOSED");
        RuleEngine engine = new RuleEngine(List.of(broken, fine));

        assertThat(engine.winner(merchant("UNCLOSED"))).contains(fine);
        assertThat(engine.invalidRules()).containsExactly(broken.id());
        assertThat(engine.rules()).containsExactly(fine);
    }

    @Test
    void compileIsCaseInsensitiveAndRejectsBadSyntax() {
        assertThat(RuleEngine.compile("cafe").matcher("BLUE BEAN CAFE").find()).isTrue();
        assertThatThrownBy(() -> RuleEngine.compile("[")).isInstanceOf(PatternSyntaxException.class);
    }
}
