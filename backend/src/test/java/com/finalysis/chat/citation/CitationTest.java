package com.finalysis.chat.citation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.analytics.MoneyFlow;
import com.finalysis.chat.ToolCall;
import com.finalysis.chat.ToolExecutor;
import com.finalysis.chat.ToolOutcome;
import com.finalysis.chat.ToolStatus;
import com.finalysis.chat.citation.Citation.CitedTxn;
import com.finalysis.chat.citation.CitationResolver.CitedAnswer;
import com.finalysis.chat.grounding.AnswerCheck;
import com.finalysis.chat.grounding.AnswerGrounding;
import com.finalysis.chat.grounding.FallbackAnswer;
import com.finalysis.chat.grounding.GroundedSet;
import com.finalysis.chat.grounding.TurnEvidence;
import com.finalysis.chat.outbound.ChatPayloadGuard;
import com.finalysis.chat.outbound.OutboundTurn;
import com.finalysis.chat.outbound.OutboundTurnFactory;
import com.finalysis.chat.tool.CategoryNamesQuery;
import com.finalysis.chat.tool.FigureBasis;
import com.finalysis.chat.tool.RefRegistry;
import com.finalysis.chat.tool.ToolName;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Citations (CHT-05, docs/plans/phase-6.md §6) on the analytics fixture, from real tool results: every cited figure
 * that is a sum adds up to its drill-downs' totals, every cited txn is a real statement line (its text equals the
 * fixture file's line), markers and figure refs are numbered as the plan says, and nothing a citation holds was sent.
 * Answers are written by the test from the results; no model.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CitationTest {

    /** One call of every tool, and of each comparison measure, over the fixture's four months. */
    static final List<ToolCall> CALLS = List.of(
            new ToolCall("get_month_totals", "{\"from\": \"2026-01\", \"to\": \"2026-04\"}"),
            new ToolCall("get_month_totals", "{\"from\": \"2026-02\", \"to\": \"2026-03\", \"account\": \"CARD-1\"}"),
            new ToolCall("get_account_totals", "{\"from\": \"2026-01\", \"to\": \"2026-04\"}"),
            new ToolCall("get_category_totals", "{\"from\": \"2026-01\", \"to\": \"2026-04\"}"),
            new ToolCall("get_category_totals", "{\"from\": \"2026-02\", \"to\": \"2026-03\", \"uncategorized\": true}"),
            new ToolCall("get_top_merchants", "{\"from\": \"2026-01\", \"to\": \"2026-04\", \"limit\": 20}"),
            new ToolCall("get_trends", "{\"month\": \"2026-03\"}"),
            new ToolCall("get_trends", "{\"month\": \"2026-04\", \"account\": \"CHECKING-1\"}"),
            new ToolCall("compare_periods", "{\"measure\": \"SPENDING\", \"a\": {\"month\": \"2026-03\"}, "
                    + "\"b\": {\"month\": \"2026-02\"}}"),
            new ToolCall("compare_periods", "{\"measure\": \"INCOME\", \"a\": {\"from\": \"2026-03\", \"to\": "
                    + "\"2026-04\"}, \"b\": {\"from\": \"2026-01\", \"to\": \"2026-02\"}}"),
            new ToolCall("compare_periods", "{\"measure\": \"NET\", \"a\": {\"month\": \"2026-04\"}, "
                    + "\"b\": {\"month\": \"2026-01\"}}"),
            new ToolCall("compare_periods", "{\"measure\": \"CATEGORY_SPENT\", \"category\": \"Shopping\", "
                    + "\"a\": {\"month\": \"2026-03\"}, \"b\": {\"month\": \"2026-02\"}}"),
            new ToolCall("compare_periods", "{\"measure\": \"CATEGORY_RECEIVED\", \"category\": \"Shopping\", "
                    + "\"a\": {\"from\": \"2026-01\", \"to\": \"2026-04\"}, \"b\": {\"month\": \"2026-02\"}}"),
            new ToolCall("list_transactions", "{\"month\": \"2026-03\", \"limit\": 10}"),
            new ToolCall("list_transactions", "{\"from\": \"2026-01\", \"to\": \"2026-04\", \"measure\": \"SPENT\", "
                    + "\"category\": \"Dining\", \"limit\": 5}"),
            new ToolCall("get_recurring_charges", "{\"status\": \"ALL\", \"limit\": 25}"),
            new ToolCall("get_alerts", "{\"from\": \"2026-01\", \"to\": \"2026-04\", \"limit\": 20}"),
            new ToolCall("get_data_coverage", "{}"),
            new ToolCall("search_transactions", "{\"query\": \"diner\", \"from\": \"2026-01\", \"to\": "
                    + "\"2026-04\", \"limit\": 10}"),
            new ToolCall("get_merchant_totals", "{\"merchants\": [\"FIXTURE DINER\", \"STREAMCO\", \"FIXTURE GROCER\"], "
                    + "\"from\": \"2026-01\", \"to\": \"2026-04\"}"),
            new ToolCall("get_merchant_totals", "{\"merchants\": [\"FIXTURE DINER\"], \"account\": \"CARD-1\"}"));

    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final JdbcTemplate jdbc;
    private final ToolExecutor executor;
    private final OutboundTurnFactory turns;
    private final AnswerGrounding grounding;
    private final CitationResolver citations;
    private final ChatPayloadGuard guard;
    private AnalyticsFixture.Loaded loaded;

    CitationTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper json, ToolExecutor executor,
                 OutboundTurnFactory turns, AnswerGrounding grounding, CitationResolver citations,
                 ChatPayloadGuard guard) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, json);
        this.jdbc = jdbc;
        this.executor = executor;
        this.turns = turns;
        this.grounding = grounding;
        this.citations = citations;
        this.guard = guard;
    }

    @BeforeAll
    void importFixture() throws Exception {
        loaded = fixture.load();
    }

    @AfterAll
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    /** A turn that ran every call, and its evidence. */
    private record Turn(OutboundTurn turn, TurnEvidence evidence) {
    }

    private Turn run(List<ToolCall> calls) {
        OutboundTurn turn = turns.start();
        List<TurnEvidence.Result> results = new ArrayList<>();
        for (ToolCall call : calls) {
            ToolOutcome outcome = executor.execute(call, turn);
            assertThat(outcome.status()).as(call.name() + " " + call.arguments()).isEqualTo(ToolStatus.OK);
            results.add(new TurnEvidence.Result(call, outcome));
        }
        return new Turn(turn, new TurnEvidence("", results, turn.refs(),
                CategoryNamesQuery.sorted(turn.categories())));
    }

    /**
     * An answer quoting every value of the results that sits under a ref, each with its ref's marker: written as the
     * result returned it (so it matches exactly), one sentence each.
     */
    private static String quoteEverything(GroundedSet set) {
        return set.values().stream().filter(v -> v.ref() != null && v.returned() != null)
                .filter(v -> v.kind() != GroundedSet.Kind.COUNT || v.magnitude().compareTo(BigDecimal.ONE) > 0)
                .map(v -> "Figure " + switch (v.kind()) {
                    case PERCENT -> v.magnitude().toPlainString() + "%";
                    case MONEY, RATIO -> "$" + v.magnitude().toPlainString();
                    case COUNT -> v.magnitude().toPlainString();
                } + " [" + v.ref() + "].")
                .collect(Collectors.joining("\n"));
    }

    @Test
    void everyCellFigureHasABasisAndEverySumAddsUpToItsDrillDowns() {
        Turn t = run(CALLS);
        GroundedSet set = GroundedSet.of(t.evidence(), guard);

        // the projection recorded what each figure of a cell rests on
        List<String> missing = set.values().stream().filter(v -> v.ref() != null)
                .filter(v -> t.turn().refs().target(v.ref()).orElseThrow() instanceof RefRegistry.Cell c
                        && !c.figures().containsKey(v.field()))
                .map(v -> v.ref() + "." + v.field()).distinct().toList();
        assertThat(missing).as("cell figures without a basis").isEmpty();

        AnswerCheck check = grounding.check(quoteEverything(set), t.evidence());
        assertThat(check.ungrounded()).isEmpty();
        CitedAnswer cited = citations.cite(check, t.turn().refs());

        List<String> wrong = new ArrayList<>();
        Set<String> sums = new HashSet<>();
        for (Citation c : cited.citations()) {
            if (!(c instanceof Citation.Total total)) {
                continue;
            }
            for (Citation.CitedFigure figure : total.figures()) {
                if (figure.relation() != FigureBasis.Relation.SUM) {
                    continue;
                }
                BigDecimal sum = figure.terms().stream()
                        .map(term -> part(term.drillDown().total(), figure.component())
                                .multiply(BigDecimal.valueOf(term.sign())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (sum.compareTo(new BigDecimal(figure.value())) != 0) {
                    wrong.add(total.label() + " " + figure.field() + "=" + figure.value() + " but its rows sum to "
                            + sum);
                }
                sums.add(figure.field().replaceAll("\\[[^]]*]", "[]"));
            }
        }
        assertThat(wrong).isEmpty();
        assertThat(sums).as("every kind of summed figure was checked").contains("income", "spending", "net",
                "refundsNet", "uncategorized.moneyOut", "uncategorized.count", "unlinkedTransfersNet",
                "dataQuality.countedTxns", "total.net", "total.moneyOut", "total.count", "months[].net",
                "months[].moneyIn", "spent", "count", "amount", "previousMonth", "changeFromPreviousMonth", "a.value",
                "b.value", "difference");
    }

    @Test
    void everyCitedTxnIsARealStatementLine() throws Exception {
        Turn t = run(CALLS);
        CitedAnswer cited = citations.cite(grounding.check(quoteEverything(GroundedSet.of(t.evidence(), guard)),
                t.evidence()), t.turn().refs());

        Map<Long, String> files = loaded.statements().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        Map<String, List<String>> lines = new HashMap<>();
        List<CitedTxn> txns = cited.citations().stream().flatMap(CitationTest::txns).toList();
        assertThat(txns).hasSizeGreaterThan(50);
        for (CitedTxn txn : txns) {
            assertThat(txn.status()).isEqualTo(CitedTxn.Status.FOUND);
            String file = files.get(txn.statement().id());
            assertThat(file).as("txn %d's statement is a fixture statement", txn.id()).isNotNull();
            List<String> fileLines = lines.computeIfAbsent(file, f -> read(AnalyticsFixture.DIR.resolve(f)));
            assertThat(txn.sourceLine().text()).as(file + ":" + txn.sourceLine().number())
                    .isEqualTo(fileLines.get(txn.sourceLine().number() - 1));
            assertThat(loaded.txns().get(file + ":" + txn.sourceLine().number())).isEqualTo(txn.id());
            assertThat(txn.fingerprint()).isNotBlank();
        }
    }

    @Test
    void nothingACitationHoldsWasSent() {
        Turn t = run(CALLS);
        CitedAnswer cited = citations.cite(grounding.check(quoteEverything(GroundedSet.of(t.evidence(), guard)),
                t.evidence()), t.turn().refs());
        String sent = t.evidence().results().stream().map(r -> r.outcome().json()).collect(Collectors.joining("\n"));

        Set<String> local = new HashSet<>();
        cited.citations().stream().flatMap(CitationTest::txns).forEach(txn -> {
            local.add(txn.sourceLine().text());
            local.add(txn.accountName());
            local.add(txn.fingerprint());
        });
        cited.citations().stream().filter(c -> c instanceof Citation.Alert)
                .forEach(c -> local.add(((Citation.Alert) c).alertKey()));
        assertThat(local).hasSizeGreaterThan(50);
        for (String value : local) {
            assertThat(sent).as("sent").doesNotContain(value);
        }
    }

    @Test
    void markersFirstThenFigureRefsInOrder() {
        Turn t = run(List.of(new ToolCall("get_month_totals", "{\"from\": \"2026-03\", \"to\": \"2026-04\"}"),
                new ToolCall("list_transactions", "{\"month\": \"2026-03\", \"limit\": 3}")));
        GroundedSet set = GroundedSet.of(t.evidence(), guard);
        GroundedSet.Value april = set.values().stream()
                .filter(v -> v.field().equals("spending") && v.result() == 0).toList().getLast();
        GroundedSet.Value march = set.values().stream()
                .filter(v -> v.field().equals("income") && v.result() == 0).toList().getFirst();

        AnswerCheck check = grounding.check("In April you spent $" + april.magnitude() + ". See [t2] and [t1, t9]. "
                + "In March you received $" + march.magnitude() + " [" + march.ref() + "].", t.evidence());
        CitedAnswer cited = citations.cite(check, t.turn().refs());

        assertThat(check.ungrounded()).isEmpty();
        // t9 was never issued: dropped, and its group keeps t1 only
        assertThat(cited.text()).isEqualTo("In April you spent $" + april.magnitude() + ". See [1] and [2]. "
                + "In March you received $" + march.magnitude() + " [3].");
        assertThat(cited.dropped()).isEqualTo(1);
        assertThat(cited.citations()).extracting(Citation::marker).containsExactly(1, 2, 3, 4);
        assertThat(cited.citations()).extracting(Citation::kind).containsExactly(Citation.Kind.TRANSACTION,
                Citation.Kind.TRANSACTION, Citation.Kind.TOTAL, Citation.Kind.TOTAL);
        // [3] is the March cell by its marker; the April cell follows as 4, cited by its figure only (no marker)
        Citation.Total marchCell = (Citation.Total) cited.citations().get(2);
        assertThat(marchCell.label()).startsWith("Totals · 2026-03");
        assertThat(marchCell.figures()).extracting(Citation.CitedFigure::field).containsExactly("income");
        Citation.Total aprilCell = (Citation.Total) cited.citations().get(3);
        assertThat(aprilCell.label()).startsWith("Totals · 2026-04");
        assertThat(aprilCell.figures()).singleElement().satisfies(f -> {
            assertThat(f.field()).isEqualTo("spending");
            assertThat(f.value()).isEqualTo(april.returned());
            assertThat(f.terms()).singleElement().satisfies(term -> {
                assertThat(term.drillDown().params()).containsEntry("month", "2026-04")
                        .containsEntry("measure", "SPENT");
                assertThat(term.drillDown().path()).isEqualTo("/api/analytics/transactions");
                assertThat(term.drillDown().sampleTxns()).hasSizeBetween(1, 5);
            });
        });
        // the txn markers resolve to the listed txns, in the order written
        assertThat(((Citation.Txn) cited.citations().get(0)).txn().id())
                .isEqualTo(((RefRegistry.Txn) t.turn().refs().target("t2").orElseThrow()).txnId());
    }

    @Test
    void seriesAndAlertsResolveToTheirRows() {
        Turn t = run(List.of(new ToolCall("get_recurring_charges", "{\"status\": \"ALL\", \"limit\": 25}"),
                new ToolCall("get_alerts", "{\"from\": \"2026-01\", \"to\": \"2026-04\", \"limit\": 20}")));
        List<String> refs = t.turn().refs().issuedRefs().stream().filter(r -> r.startsWith("s") || r.startsWith("a"))
                .toList();
        assertThat(refs).anyMatch(r -> r.startsWith("s")).anyMatch(r -> r.startsWith("a"));

        CitedAnswer cited = citations.cite(grounding.check("See " + refs.stream().map(r -> "[" + r + "]")
                .collect(Collectors.joining(" ")) + ".", t.evidence()), t.turn().refs());

        for (Citation c : cited.citations()) {
            switch (c) {
                case Citation.Series s -> {
                    assertThat(s.drillDown().params()).containsEntry("merchant", s.merchant())
                            .containsEntry("accountId", Long.toString(s.accountId()));
                    assertThat(s.drillDown().count()).isGreaterThanOrEqualTo(2);
                    assertThat(s.drillDown().sampleTxns()).allSatisfy(txn ->
                            assertThat(txn.merchant()).isEqualTo(s.merchant()));
                }
                case Citation.Alert a -> {
                    assertThat(a.alertKey()).isNotBlank();
                    assertThat(a.txns()).allSatisfy(txn -> assertThat(txn.status()).isEqualTo(CitedTxn.Status.FOUND));
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM txn WHERE id IN ("
                            + a.txns().stream().map(x -> x.id().toString()).collect(Collectors.joining(","))
                            + ")", Integer.class)).isEqualTo(a.txns().size());
                }
                default -> throw new AssertionError("unexpected " + c.kind());
            }
        }
    }

    /** The comparison cell's figure {@code a.value}, quoted with the cell's marker, and its citation. */
    private Citation.Total citeSideA(String arguments, String written) {
        Turn t = run(List.of(new ToolCall("compare_periods", arguments)));
        String ref = ((com.finalysis.chat.tool.view.ComparisonView) t.evidence().results().getFirst().outcome().view())
                .ref();
        AnswerCheck check = grounding.check("It was " + written + " [" + ref + "].", t.evidence());
        assertThat(check.ungrounded()).isEmpty();
        return (Citation.Total) citations.cite(check, t.turn().refs()).citations().getFirst();
    }

    private static BigDecimal sum(Citation.CitedFigure figure) {
        return figure.terms().stream().map(term -> part(term.drillDown().total(), figure.component())
                .multiply(BigDecimal.valueOf(term.sign()))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Step 4 regression: a NET side drilled into every row of its months, linked and unlinked transfers included.
     * February's net is 1,313.27 while all its rows sum to 1,063.27 (the unlinked OUTSIDE CARD AUTOPAY, -250.00;
     * expected-month-totals.csv's {@code sum_all}), so the old drill-down didn't add up.
     */
    @Test
    void aNetComparisonDrillsIntoIncomeAndSpendingRowsOnlyNotTransfers() {
        Citation.Total cell = citeSideA("{\"measure\": \"NET\", \"a\": {\"month\": \"2026-02\"}, "
                + "\"b\": {\"month\": \"2026-01\"}}", "$1313.27");

        Citation.CitedFigure net = cell.figures().getFirst();
        assertThat(net.field()).isEqualTo("a.value");
        assertThat(net.value()).isEqualTo("1313.27");
        assertThat(sum(net)).isEqualByComparingTo("1313.27");
        assertThat(net.terms()).extracting(term -> term.drillDown().params().get("measure"))
                .containsExactlyInAnyOrder("RECEIVED", "SPENT");
        assertThat(cell.drillDowns()).allSatisfy(drill ->
                assertThat(drill.params()).containsKey("measure").doesNotContainKey("bucket"));
        assertThat(cell.drillDowns().stream().flatMap(d -> d.sampleTxns().stream()))
                .noneMatch(txn -> txn.merchant() != null && txn.merchant().contains("AUTOPAY"));
    }

    /**
     * Step 4 regression: a CATEGORY_SPENT side drilled into {@code measure=SPENT}, which nets the category's refund
     * rows into its spending; the service counts its expense rows only. Refunds &amp; Rebates has one refund row in
     * March (ONLINE RETURN CREDIT, +15.00) and no expense rows, so it spent 0.00, and the old drill-down said -15.00.
     */
    @Test
    void aCategorySpentComparisonDrillsIntoExpenseRowsOnlyNotRefunds() {
        Citation.Total cell = citeSideA("{\"measure\": \"CATEGORY_SPENT\", \"category\": \"Refunds & Rebates\", "
                + "\"a\": {\"month\": \"2026-03\"}, \"b\": {\"month\": \"2026-02\"}}", "$0.00");

        Citation.CitedFigure spent = cell.figures().stream().filter(f -> f.field().equals("a.value")).findFirst()
                .orElseThrow();
        assertThat(spent.value()).isEqualTo("0.00");
        assertThat(sum(spent)).isEqualByComparingTo("0.00");
        assertThat(spent.terms()).singleElement().satisfies(term -> {
            assertThat(term.drillDown().params()).containsEntry("bucket", "EXPENSE").doesNotContainKey("measure");
            assertThat(term.drillDown().count()).isZero();
        });
        assertThat(cell.drillDowns()).allSatisfy(drill ->
                assertThat(drill.params()).containsEntry("bucket", "EXPENSE").doesNotContainKey("measure"));
    }

    /** Every tool's template, on its real result: lines with issued markers, and as grounded as a model's answer. */
    @Test
    void theFallbackOfEveryToolIsGroundedAndCited() {
        for (ToolCall call : CALLS) {
            Turn t = run(List.of(call));
            String fallback = FallbackAnswer.render(t.evidence());
            assertThat(fallback).as(call.name()).startsWith("I couldn't confirm every figure");
            assertThat(fallback.lines().count()).as(call.name()).isGreaterThan(1);
            if (!call.name().equals(ToolName.GET_DATA_COVERAGE.wire())) {
                AnswerCheck check = grounding.check(fallback, t.evidence());
                assertThat(check.ungrounded()).as(call.name() + ": " + fallback).isEmpty();
                assertThat(check.dropped()).isZero();
            }
            CitedAnswer cited = citations.citeFallback(fallback, t.turn().refs());
            assertThat(cited.dropped()).isZero();
            assertThat(cited.text()).doesNotContainPattern("\\[[tcas]\\d+");
        }
    }

    private static Stream<CitedTxn> txns(Citation c) {
        return switch (c) {
            case Citation.Txn t -> Stream.of(t.txn());
            case Citation.Alert a -> a.txns().stream();
            case Citation.Series s -> s.drillDown().sampleTxns().stream();
            case Citation.Total total -> Stream.concat(total.drillDowns().stream(),
                    total.figures().stream().flatMap(f -> f.terms().stream().map(Citation.Term::drillDown)))
                    .flatMap(d -> d.sampleTxns().stream());
        };
    }

    private static BigDecimal part(MoneyFlow total, FigureBasis.Component component) {
        return switch (component) {
            case NET -> total.net();
            case MONEY_OUT -> total.moneyOut();
            case MONEY_IN -> total.moneyIn();
            case COUNT -> BigDecimal.valueOf(total.count());
        };
    }

    private static List<String> read(java.nio.file.Path file) {
        try {
            return Files.readAllLines(file);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
