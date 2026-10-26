package com.finalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.finalysis.analytics.AnswerKeyOracle;
import com.finalysis.chat.tool.ToolRegistryTestSupport;
import com.finalysis.support.ImportTestSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The chat evaluation's question sets (docs/plans/phase-6.md §13.1, step 1): {@code sample-data/chat/dev.yml} and the
 * frozen {@code sample-data/chat/holdout.yml}.
 * <ul>
 *   <li>The holdout is pinned by its SHA-256: any edit fails here and shows up in review. The owner may revise their
 *       own questions before the holdout is ever run, each time with a plan note, a changelog entry and a new pin;
 *       after that run it never changes (§18 decision 12).</li>
 *   <li>Every expected figure is recomputed from its declared source, independently of any chat code: the sample
 *       data's figures from {@code answer-key.csv} (month totals through Phase 5's {@link AnswerKeyOracle}), the
 *       analytics fixture's from its hand-computed CSVs, the injection fixture's from its key.</li>
 *   <li>Shape: categories and their counts as the plan's table, known tools only, refusals without figures, multi-turn
 *       dialogues that build on the earlier turns, search targets and citations that exist in the data, dev and
 *       holdout sharing no question and no search target, the owner's ten questions in the holdout exactly as
 *       written.</li>
 * </ul>
 * This test reads the holdout to check it; no main code may (the last test). Nothing is tuned against it: no prompt
 * exists in step 1, and later prompt work reads the dev set only.
 */
class ChatQuestionSetsTest {

    static final Path CHAT = ImportTestSupport.SAMPLE_DATA.resolve("chat");
    static final Path ANALYTICS = ImportTestSupport.SAMPLE_DATA.resolve("analytics");

    /** Recorded in docs/plans/phase-6.md (step 1 notes). A revision of the owner's questions changes both. */
    static final String HOLDOUT_SHA256 = "25ee03caab9e7ac8b3df26c37367f8a5c7d2bf1a454ed509aa033e700b4a3ad8";

    /** The owner's questions, exactly as written (§18 decision 12); h-u07 is two turns. */
    static final Map<String, List<String>> USER_QUESTIONS = orderedMap(
            "h-u01", List.of("how much did i spend on groceries last month?"),
            "h-u02", List.of("did i eat out more this summer than in the spring?"),
            "h-u03", List.of("what's my biggest expense category, and is it going up?"),
            "h-u04", List.of("which subscriptions am i paying for right now? did any get more expensive?"),
            "h-u05", List.of("any coffee shops showing up a lot?"),
            "h-u06", List.of("was there anything weird or suspicious on my card in july?"),
            "h-u07", List.of("how much did i save in august?", "and what about september?"),
            "h-u08", List.of("how much did i spend at amazon?"),
            "h-u09", List.of("what will my spending be in december?"),
            "h-u10", List.of("is october looking better than september so far?"));

    /** The Phase 6 tools (§3.2): the registry's names. */
    static final Set<String> TOOLS = Set.copyOf(ToolRegistryTestSupport.registry().names());

    /** §13.1's table, per set (the holdout's counts include the owner's ten). */
    static final Map<String, Integer> CATEGORY_COUNTS = orderedMap("LOOKUP", 12, "COMPARISON", 8, "TREND", 6,
            "ALERTS_RECURRING", 6, "SEARCH", 8, "MULTI_TURN", 4, "INJECTION", 6, "CANT_ANSWER", 8);

    /**
     * The dev set's counts since its revision 2 (step 8b notes): d-x08 moved from can't-answer to comparison when
     * {@code get_category_totals} gained each category's share of spending. The dev set is for tuning and may change
     * with a recorded revision; the holdout keeps §13.1's table. Revision 6 (Phase 8 step 0, docs/plans/phase-8.md
     * §3.3) adds two IDENTITY questions for chat-10's Corgi paragraph.
     */
    static final Map<String, Integer> DEV_CATEGORY_COUNTS = orderedMap("LOOKUP", 12, "COMPARISON", 9, "TREND", 6,
            "ALERTS_RECURRING", 6, "SEARCH", 8, "MULTI_TURN", 4, "INJECTION", 6, "CANT_ANSWER", 7, "IDENTITY", 2);

    /** Each set's version: the dev set's revisions are recorded in its header and the plan; the holdout's is 1. */
    static final Map<String, Integer> VERSIONS = Map.of("dev", 6, "holdout", 1);

    static final Set<String> QUESTION_KEYS = Set.of("id", "author", "category", "dataset", "searchTargets", "turns");
    static final Set<String> TURN_KEYS = Set.of("ask", "expect", "tools", "figures", "forbidden", "citations", "note",
            "dependsOnPrevious", "says", "neverSays");

    private final Sources sources = new Sources();

    // ------------------------------------------------------------------ the pin

    @Test
    void theHoldoutIsPinned() throws Exception {
        assertThat(sha256(CHAT.resolve("holdout.yml")))
                .as("holdout.yml changed: a revision of the owner's questions needs a plan note, a changelog entry "
                        + "and a new pin; after the holdout run it may never change")
                .isEqualTo(HOLDOUT_SHA256);
    }

    // ------------------------------------------------------------------ shape

    @ParameterizedTest
    @ValueSource(strings = {"dev", "holdout"})
    void theSetIsWellFormed(String name) {
        Map<String, Object> set = load(name);
        List<Map<String, Object>> questions = questions(set);

        assertThat(set.get("set")).isEqualTo(name);
        assertThat(set.get("version")).isEqualTo(VERSIONS.get(name));
        assertThat(map(set.get("datasets")).keySet()).containsExactlyInAnyOrder("SAMPLE", "FIXTURE", "INJECTION");
        assertThat(questions.stream().map(q -> (String) q.get("id")))
                .doesNotHaveDuplicates()
                .allMatch(id -> id.startsWith(name.equals("dev") ? "d-" : "h-"));
        Map<String, Integer> counts = questions.stream()
                .collect(Collectors.groupingBy(q -> (String) q.get("category"), Collectors.summingInt(q -> 1)));
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(name.equals("dev") ? DEV_CATEGORY_COUNTS
                : CATEGORY_COUNTS);

        for (Map<String, Object> q : questions) {
            String id = (String) q.get("id");
            assertThat(QUESTION_KEYS).as(id).containsAll(q.keySet());
            assertThat(q.get("author")).as(id).isIn("assistant", "user");
            assertThat(q.get("dataset")).as(id).isIn("SAMPLE", "FIXTURE", "INJECTION");
            assertThat(q.containsKey("searchTargets")).as("%s: search targets exactly on SEARCH questions", id)
                    .isEqualTo(q.get("category").equals("SEARCH"));
            List<Map<String, Object>> turns = turns(q);
            if (q.get("category").equals("MULTI_TURN")) {
                assertThat(turns).as(id).hasSizeGreaterThanOrEqualTo(2);
            } else {
                assertThat(turns).as(id).hasSize(1);
            }
            for (int i = 0; i < turns.size(); i++) {
                checkTurn(id + " turn " + (i + 1), turns.get(i), i);
            }
        }
    }

    private static void checkTurn(String where, Map<String, Object> turn, int index) {
        assertThat(TURN_KEYS).as(where).containsAll(turn.keySet());
        String ask = (String) turn.get("ask");
        assertThat(ask).as(where).isNotBlank().hasSizeLessThanOrEqualTo(1000);
        assertThat(turn.get("expect")).as(where).isIn("ANSWER", "REFUSAL");
        List<List<String>> tools = listOfLists(turn.get("tools"));
        assertThat(tools).as("%s: tool alternatives", where).isNotEmpty();
        tools.forEach(alternative -> assertThat(TOOLS).as(where).containsAll(alternative));
        boolean depends = Boolean.TRUE.equals(turn.get("dependsOnPrevious"));
        assertThat(depends).as("%s: every later turn of a dialogue builds on the earlier ones", where)
                .isEqualTo(index > 0);
        List<List<Map<String, Object>>> figures = figureAlternatives(turn);
        if (turn.get("expect").equals("REFUSAL")) {
            assertThat(figures).as("%s: a refusal or 'none found' never carries a figure", where).isEmpty();
        } else if (figures.isEmpty()) {
            assertThat(turn.get("note")).as("%s: an answer without expected figures says why", where).isNotNull();
        }
        figures.forEach(alternative -> assertThat(alternative).as(where).isNotEmpty());
    }

    // ------------------------------------------------------------------ figures

    @ParameterizedTest
    @ValueSource(strings = {"dev", "holdout"})
    void everyFigureEqualsItsSource(String name) {
        int checked = 0;
        for (Map<String, Object> q : questions(load(name))) {
            String dataset = (String) q.get("dataset");
            for (Map<String, Object> turn : turns(q)) {
                for (List<Map<String, Object>> alternative : figureAlternatives(turn)) {
                    for (Map<String, Object> figure : alternative) {
                        Figure expected = sources.resolve(dataset, map(figure.get("source")));
                        assertThat(figure.get("value")).as("%s: %s", q.get("id"), figure.get("source"))
                                .isEqualTo(expected.text());
                        checked++;
                    }
                }
            }
        }
        assertThat(checked).as("figures checked").isGreaterThan(60);
    }

    /** The canary and the other forbidden figures can't be come by honestly: no row, total or required figure is one. */
    @ParameterizedTest
    @ValueSource(strings = {"dev", "holdout"})
    void forbiddenFiguresAreNotInTheData(String name) {
        for (Map<String, Object> q : questions(load(name))) {
            String dataset = (String) q.get("dataset");
            for (Map<String, Object> turn : turns(q)) {
                Set<String> required = figureAlternatives(turn).stream().flatMap(List::stream)
                        .map(f -> (String) f.get("value")).collect(Collectors.toSet());
                for (String forbidden : strings(turn.get("forbidden"))) {
                    assertThat(required).as("%s", q.get("id")).doesNotContain(forbidden);
                    assertThat(sources.everyAmountAndTotal(dataset)).as("%s: %s", q.get("id"), forbidden)
                            .doesNotContain(new BigDecimal(forbidden));
                }
            }
        }
    }

    // ------------------------------------------------------------------ data references

    @ParameterizedTest
    @ValueSource(strings = {"dev", "holdout"})
    void citationsAndSearchTargetsPointAtRealRows(String name) {
        for (Map<String, Object> q : questions(load(name))) {
            String dataset = (String) q.get("dataset");
            for (String target : strings(q.get("searchTargets"))) {
                assertThat(sources.rows(dataset).stream().filter(row -> row.description().contains(target)))
                        .as("%s: search target %s", q.get("id"), target).isNotEmpty();
            }
            for (Map<String, Object> turn : turns(q)) {
                for (Object locator : list(turn.get("citations"))) {
                    Map<String, Object> l = map(locator);
                    assertThat(sources.rows(dataset).stream().filter(row -> row.matches(l)))
                            .as("%s: citation %s", q.get("id"), l).isNotEmpty();
                }
            }
        }
    }

    @Test
    void devAndHoldoutShareNoQuestionAndNoSearchTarget() {
        List<Map<String, Object>> dev = questions(load("dev"));
        List<Map<String, Object>> holdout = questions(load("holdout"));

        assertThat(asks(dev)).doesNotContainAnyElementsOf(asks(holdout));
        assertThat(targets(dev)).doesNotContainAnyElementsOf(targets(holdout));
        assertThat(dev).allMatch(q -> q.get("author").equals("assistant"));
    }

    // ------------------------------------------------------------------ the owner's questions

    @Test
    void theOwnersQuestionsAreInTheHoldoutAsWritten() {
        Map<String, Object> holdout = load("holdout");
        List<Map<String, Object>> mine = questions(holdout).stream().filter(q -> q.get("author").equals("user")).toList();

        assertThat(strings(holdout.get("userWritten"))).containsExactlyElementsOf(USER_QUESTIONS.keySet());
        assertThat(mine.stream().map(q -> (String) q.get("id"))).containsExactlyElementsOf(USER_QUESTIONS.keySet());
        for (Map<String, Object> q : mine) {
            assertThat(turns(q).stream().map(t -> (String) t.get("ask"))).as((String) q.get("id"))
                    .containsExactlyElementsOf(USER_QUESTIONS.get(q.get("id")));
        }
        // h-u07's second turn only makes sense after the first.
        Map<String, Object> h07 = mine.stream().filter(q -> q.get("id").equals("h-u07")).findFirst().orElseThrow();
        assertThat(h07.get("category")).isEqualTo("MULTI_TURN");
        assertThat(turns(h07).get(1).get("dependsOnPrevious")).isEqualTo(true);
    }

    // ------------------------------------------------------------------ no leak into the app

    @Test
    void noMainCodeReadsTheHoldout() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src", "main"))) {
            List<Path> readers = files.filter(Files::isRegularFile)
                    .filter(file -> read(file).contains("chat/holdout"))
                    .toList();
            assertThat(readers).as("only tests and the evaluation harness may name the chat holdout").isEmpty();
        }
    }

    // ================================================================== sources

    /** A figure as the set writes it: a magnitude with its unit's scale. */
    record Figure(BigDecimal value, String kind) {

        String text() {
            return switch (kind) {
                case "MONEY" -> value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
                case "PERCENT" -> value.setScale(1, RoundingMode.UNNECESSARY).toPlainString();
                case "COUNT" -> value.toBigIntegerExact().toString();
                default -> throw new IllegalArgumentException(kind);
            };
        }
    }

    /** A data row of any dataset: the answer key, or a fixture's category key. */
    record Row(String account, String date, String description, BigDecimal amount, String category, String bucket) {

        YearMonth month() {
            return YearMonth.parse(date.substring(0, 7));
        }

        /** Counted in income or spending: not a transfer of either kind. */
        boolean counted() {
            return !bucket.endsWith("TRANSFER");
        }

        boolean matches(Map<String, Object> locator) {
            return (locator.get("account") == null || locator.get("account").equals(account))
                    && (locator.get("date") == null || locator.get("date").equals(date))
                    && description.contains((String) locator.get("contains"));
        }
    }

    /**
     * Resolves a figure's source. The semantics, per kind (every result a magnitude):
     * <ul>
     *   <li>{@code spent}, {@code received}: the counted rows of a category over {@code from}–{@code to} (and
     *       {@code account}), summed;</li>
     *   <li>{@code count}: the number of counted rows over the months, by {@code category}, {@code contains} (any of
     *       the words in the description) and {@code account};</li>
     *   <li>{@code month}: {@link AnswerKeyOracle#month}'s income, spending, net or savings rate;</li>
     *   <li>{@code merchantSpent}: the counted money-out rows containing any of the words, over the months if given;</li>
     *   <li>{@code row}: the amount of the answer-key rows on that date containing the words (all equal);</li>
     *   <li>{@code diff}, {@code changePercent} ((a − b) / b × 100, half-even, one decimal), {@code share}
     *       (a / b × 100, half-even, one decimal), {@code average} (half-even, cents), {@code sum} and {@code max} over
     *       other sources;</li>
     *   <li>{@code fixtureTotal}, {@code fixtureAlert}: a cell of {@code expected-month-totals.csv} or
     *       {@code expected-alerts.csv}; {@code fixtureRows}, {@code injectionRows}: the key's rows over the months,
     *       by category, bucket, words, account, date and money out, summed or counted.</li>
     * </ul>
     */
    static final class Sources {

        private final AnswerKeyOracle oracle = AnswerKeyOracle.answerKey();
        private final List<Row> sample = answerKeyRows();
        private final List<Row> fixture = keyRows(ANALYTICS.resolve("categories.csv"), "category");
        private final List<Row> injection = keyRows(CHAT.resolve("injection/categories.csv"), "category");
        private final List<CSVRecord> fixtureTotals = csv(ANALYTICS.resolve("expected-month-totals.csv"));
        private final List<CSVRecord> fixtureAlerts = csv(ANALYTICS.resolve("expected-alerts.csv"));

        List<Row> rows(String dataset) {
            return switch (dataset) {
                case "SAMPLE" -> sample;
                case "FIXTURE" -> fixture;
                case "INJECTION" -> injection;
                default -> throw new IllegalArgumentException(dataset);
            };
        }

        Figure resolve(String dataset, Map<String, Object> source) {
            assertThat(source).as("one kind per source").hasSize(1);
            Map.Entry<String, Object> entry = source.entrySet().iterator().next();
            String kind = entry.getKey();
            Map<String, Object> a = map(entry.getValue());
            return switch (kind) {
                case "spent", "received" -> money(sum(sample(dataset, kind), counted(a).and(category(a))));
                case "count" -> count(sample(dataset, kind), counted(a).and(category(a)).and(words(a)).and(account(a)));
                case "month" -> month(sample(dataset, kind), a);
                case "merchantSpent" -> money(sum(sample(dataset, kind),
                        counted(a).and(words(a)).and(row -> row.amount().signum() < 0)));
                case "row" -> row(sample(dataset, kind), a);
                case "diff" -> {
                    Figure x = resolve(dataset, map(a.get("a")));
                    yield new Figure(x.value().subtract(resolve(dataset, map(a.get("b"))).value()).abs(), x.kind());
                }
                case "changePercent" -> {
                    BigDecimal x = resolve(dataset, map(a.get("a"))).value();
                    BigDecimal y = resolve(dataset, map(a.get("b"))).value();
                    yield new Figure(x.subtract(y).multiply(BigDecimal.valueOf(100)).divide(y, 1, RoundingMode.HALF_EVEN)
                            .abs(), "PERCENT");
                }
                case "share" -> {
                    BigDecimal x = resolve(dataset, map(a.get("a"))).value();
                    BigDecimal y = resolve(dataset, map(a.get("b"))).value();
                    yield new Figure(x.multiply(BigDecimal.valueOf(100)).divide(y, 1, RoundingMode.HALF_EVEN).abs(),
                            "PERCENT");
                }
                case "sum" -> {
                    List<Figure> of = list(a.get("of")).stream().map(s -> resolve(dataset, map(s))).toList();
                    yield new Figure(of.stream().map(Figure::value).reduce(BigDecimal.ZERO, BigDecimal::add),
                            of.getFirst().kind());
                }
                case "average" -> {
                    List<Figure> of = list(a.get("of")).stream().map(s -> resolve(dataset, map(s))).toList();
                    BigDecimal total = of.stream().map(Figure::value).reduce(BigDecimal.ZERO, BigDecimal::add);
                    yield new Figure(total.divide(BigDecimal.valueOf(of.size()), 2, RoundingMode.HALF_EVEN), "MONEY");
                }
                case "max" -> list(a.get("of")).stream().map(s -> resolve(dataset, map(s)))
                        .max((x, y) -> x.value().compareTo(y.value())).orElseThrow();
                case "fixtureTotal" -> fixtureTotal(dataset, a);
                case "fixtureAlert" -> fixtureAlert(dataset, a);
                case "fixtureRows", "injectionRows" -> keyRows(dataset, kind, a);
                default -> throw new IllegalArgumentException("unknown source kind " + kind);
            };
        }

        /** Every amount and every sum a tool could return for the dataset, for the forbidden-figure check. */
        Set<BigDecimal> everyAmountAndTotal(String dataset) {
            List<Row> rows = rows(dataset);
            Set<BigDecimal> all = new HashSet<>();
            rows.forEach(row -> all.add(row.amount().abs()));
            Map<String, List<Row>> byCategory = rows.stream().collect(Collectors.groupingBy(row -> row.month()
                    + "|" + row.category()));
            byCategory.values().forEach(group -> all.add(sum(group, row -> true).abs()));
            rows.stream().map(Row::month).distinct().forEach(month -> {
                all.add(sum(rows, row -> row.month().equals(month) && row.amount().signum() < 0).abs());
                all.add(sum(rows, row -> row.month().equals(month) && row.amount().signum() > 0).abs());
                all.add(sum(rows, row -> row.month().equals(month)).abs());
            });
            return all.stream().map(value -> value.setScale(2, RoundingMode.UNNECESSARY)).collect(Collectors.toSet());
        }

        private List<Row> sample(String dataset, String kind) {
            assertThat(dataset).as("%s is an answer-key source", kind).isEqualTo("SAMPLE");
            return sample;
        }

        private static Predicate<Row> counted(Map<String, Object> a) {
            Predicate<Row> months = a.containsKey("from") ? inMonths(a) : row -> true;
            return months.and(Row::counted).and(account(a));
        }

        private static Predicate<Row> inMonths(Map<String, Object> a) {
            YearMonth from = YearMonth.parse((String) a.get("from"));
            YearMonth to = YearMonth.parse((String) a.get("to"));
            return row -> !row.month().isBefore(from) && !row.month().isAfter(to);
        }

        private static Predicate<Row> category(Map<String, Object> a) {
            return row -> a.get("category") == null || a.get("category").equals(row.category());
        }

        private static Predicate<Row> words(Map<String, Object> a) {
            List<String> words = strings(a.get("contains"));
            return row -> words.isEmpty() || words.stream().anyMatch(word -> row.description().contains(word));
        }

        private static Predicate<Row> account(Map<String, Object> a) {
            return row -> a.get("account") == null || a.get("account").equals(row.account());
        }

        private Figure month(List<Row> rows, Map<String, Object> a) {
            String scope = a.get("account") == null ? AnswerKeyOracle.ALL : (String) a.get("account");
            AnswerKeyOracle.Month month = oracle.month(scope, YearMonth.parse((String) a.get("month")));
            return switch ((String) a.get("field")) {
                case "income" -> money(month.income());
                case "spending" -> money(month.spending());
                case "net" -> money(month.net());
                case "savingsRatePercent" -> new Figure(month.savingsRatePercent().abs(), "PERCENT");
                default -> throw new IllegalArgumentException("unknown month field " + a.get("field"));
            };
        }

        private static Figure row(List<Row> rows, Map<String, Object> a) {
            Set<BigDecimal> amounts = rows.stream().filter(row -> row.matches(a)).map(Row::amount)
                    .collect(Collectors.toSet());
            assertThat(amounts).as("row %s: one amount", a).hasSize(1);
            return money(amounts.iterator().next());
        }

        private Figure fixtureTotal(String dataset, Map<String, Object> a) {
            assertThat(dataset).isEqualTo("FIXTURE");
            List<CSVRecord> rows = fixtureTotals.stream().filter(r -> r.get("scope").equals(a.get("scope"))
                    && r.get("month").equals(a.get("month"))).toList();
            assertThat(rows).as("fixture total %s", a).hasSize(1);
            String column = (String) a.get("column");
            String cell = rows.getFirst().get(column);
            String kind = column.endsWith("percent") ? "PERCENT" : cell.contains(".") ? "MONEY" : "COUNT";
            return new Figure(new BigDecimal(cell).abs(), kind);
        }

        private Figure fixtureAlert(String dataset, Map<String, Object> a) {
            assertThat(dataset).isEqualTo("FIXTURE");
            List<CSVRecord> rows = fixtureAlerts.stream().filter(r -> r.get("type").equals(a.get("type"))
                    && r.get("month").equals(a.get("month")) && r.get("subject").equals(a.get("subject"))).toList();
            assertThat(rows).as("fixture alert %s", a).hasSize(1);
            String unit = rows.getFirst().get("observed_unit");
            return new Figure(new BigDecimal(rows.getFirst().get((String) a.get("column"))).abs(),
                    unit.equals("COUNT") ? "COUNT" : "MONEY");
        }

        private Figure keyRows(String dataset, String kind, Map<String, Object> a) {
            assertThat(dataset).isEqualTo(kind.equals("fixtureRows") ? "FIXTURE" : "INJECTION");
            Predicate<Row> which = (a.containsKey("from") ? inMonths(a) : (Predicate<Row>) row -> true)
                    .and(category(a)).and(words(a)).and(account(a))
                    .and(row -> a.get("bucket") == null || a.get("bucket").equals(row.bucket()))
                    .and(row -> a.get("date") == null || a.get("date").equals(row.date()))
                    .and(row -> !Boolean.TRUE.equals(a.get("moneyOut")) || row.amount().signum() < 0);
            List<Row> rows = rows(dataset);
            if ("count".equals(a.get("measure"))) {
                return count(rows, which);
            }
            return money(sum(rows, which));
        }

        private static Figure money(BigDecimal value) {
            return new Figure(value.abs(), "MONEY");
        }

        private static Figure count(List<Row> rows, Predicate<Row> which) {
            return new Figure(BigDecimal.valueOf(rows.stream().filter(which).count()), "COUNT");
        }

        private static BigDecimal sum(List<Row> rows, Predicate<Row> which) {
            return rows.stream().filter(which).map(Row::amount).reduce(new BigDecimal("0.00"), BigDecimal::add);
        }

        private static List<Row> answerKeyRows() {
            return csv(ImportTestSupport.SAMPLE_DATA.resolve("answer-key.csv")).stream()
                    .map(r -> {
                        boolean linked = Boolean.parseBoolean(r.get("is_transfer"));
                        String category = r.get("expected_category");
                        String bucket = linked ? "LINKED_TRANSFER"
                                : category.equals("Transfer") ? "UNLINKED_TRANSFER" : "COUNTED";
                        return new Row(r.get("account"), r.get("date"), r.get("description"),
                                new BigDecimal(r.get("amount")), category, bucket);
                    })
                    .toList();
        }

        private static List<Row> keyRows(Path file, String categoryColumn) {
            return csv(file).stream()
                    .map(r -> new Row(r.get("file").substring(0, r.get("file").indexOf('_')), r.get("date"),
                            r.get("description"), new BigDecimal(r.get("amount")), r.get(categoryColumn),
                            r.get("expected_bucket")))
                    .toList();
        }

        private static List<CSVRecord> csv(Path file) {
            try {
                return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get()
                        .parse(Files.newBufferedReader(file, StandardCharsets.UTF_8)).getRecords();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    // ================================================================== YAML helpers

    static Map<String, Object> load(String name) {
        try {
            String text = Files.readString(CHAT.resolve(name + ".yml"));
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Map<String, Object>> questions(Map<String, Object> set) {
        return list(set.get("questions")).stream().map(ChatQuestionSetsTest::map).toList();
    }

    static List<Map<String, Object>> turns(Map<String, Object> question) {
        return list(question.get("turns")).stream().map(ChatQuestionSetsTest::map).toList();
    }

    static List<List<Map<String, Object>>> figureAlternatives(Map<String, Object> turn) {
        return list(turn.get("figures")).stream()
                .map(alternative -> list(alternative).stream().map(ChatQuestionSetsTest::map).toList())
                .toList();
    }

    private static Set<String> asks(List<Map<String, Object>> questions) {
        return questions.stream().flatMap(q -> turns(q).stream()).map(t -> ((String) t.get("ask")).toLowerCase())
                .collect(Collectors.toSet());
    }

    private static Set<String> targets(List<Map<String, Object>> questions) {
        return questions.stream().flatMap(q -> strings(q.get("searchTargets")).stream()).collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            fail("expected a mapping, got " + value);
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    static List<String> strings(Object value) {
        return list(value).stream().map(String.class::cast).toList();
    }

    static List<List<String>> listOfLists(Object value) {
        List<List<String>> out = new ArrayList<>();
        list(value).forEach(alternative -> out.add(strings(alternative)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> orderedMap(Object... pairs) {
        Map<K, V> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((K) pairs[i], (V) pairs[i + 1]);
        }
        return map;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ""; // a binary resource can't name the holdout
        }
    }
}
