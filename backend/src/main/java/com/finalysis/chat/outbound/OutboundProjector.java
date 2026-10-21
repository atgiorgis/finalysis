package com.finalysis.chat.outbound;

import static com.finalysis.chat.tool.FigureBasis.Component.COUNT;
import static com.finalysis.chat.tool.FigureBasis.Component.MONEY_IN;
import static com.finalysis.chat.tool.FigureBasis.Component.MONEY_OUT;
import static com.finalysis.chat.tool.FigureBasis.Component.NET;

import com.finalysis.alert.Alert;
import com.finalysis.alert.AlertBasis;
import com.finalysis.alert.AlertDetails;
import com.finalysis.alert.AlertListResponse;
import com.finalysis.alert.Quantity;
import com.finalysis.alert.TxnRef;
import com.finalysis.analytics.AccountTotalsResponse;
import com.finalysis.analytics.Average;
import com.finalysis.analytics.CategoryRef;
import com.finalysis.analytics.CategoryTotalsResponse;
import com.finalysis.analytics.ComparisonResponse;
import com.finalysis.analytics.CoverageResponse;
import com.finalysis.analytics.DataQuality;
import com.finalysis.analytics.DrillDownFilter;
import com.finalysis.analytics.DrillDownResponse;
import com.finalysis.analytics.MerchantTotalsResponse;
import com.finalysis.analytics.MoneyFlow;
import com.finalysis.analytics.MonthFlag;
import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.MonthTotals;
import com.finalysis.analytics.MonthlyTotalsResponse;
import com.finalysis.analytics.RecurringCharge;
import com.finalysis.analytics.RecurringChargesResponse;
import com.finalysis.analytics.TrendMeasure;
import com.finalysis.analytics.TrendRow;
import com.finalysis.analytics.TrendsResponse;
import com.finalysis.chat.tool.CategoryNamesQuery;
import com.finalysis.chat.tool.DataNote;
import com.finalysis.chat.tool.FieldProblem;
import com.finalysis.chat.tool.FigureBasis;
import com.finalysis.chat.tool.RefRegistry;
import com.finalysis.chat.tool.ToolProblem;
import com.finalysis.chat.tool.ToolRead;
import com.finalysis.chat.tool.view.AccountTotalsView;
import com.finalysis.chat.tool.view.AlertsView;
import com.finalysis.chat.tool.view.AverageView;
import com.finalysis.chat.tool.view.CategoryTotalsView;
import com.finalysis.chat.tool.view.ComparisonView;
import com.finalysis.chat.tool.view.CoverageView;
import com.finalysis.chat.tool.view.DataNotesView;
import com.finalysis.chat.tool.view.FlagView;
import com.finalysis.chat.tool.view.FlowView;
import com.finalysis.chat.tool.view.MonthCoverageView;
import com.finalysis.chat.tool.view.MonthTotalsView;
import com.finalysis.chat.tool.view.RecurringView;
import com.finalysis.chat.tool.view.TopMerchantsView;
import com.finalysis.chat.tool.view.TransactionsView;
import com.finalysis.chat.tool.view.TrendsView;
import com.finalysis.chat.tool.view.ToolErrorView;
import com.finalysis.chat.tool.view.TxnView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Builds each tool's outbound view from the Phase 5 records (docs/plans/phase-6.md §12.1, §12.2): an explicit copy, so a
 * field not copied is not sent. Accounts become aliases, merchants their outbound text, rows get per-turn refs whose
 * targets (ids, alert keys, drill-down parameters) stay in the turn. Balances ({@code netChange}), linked-transfer lines
 * of the month totals, statement ids, review times, account names and raw text have no place in any view. Money is
 * formatted as the API writes it; nothing is computed here.
 */
@Component
public class OutboundProjector {

    /** At most this many txns of an alert are sent (§3.2). */
    static final int ALERT_TXNS = 5;

    // ------------------------------------------------------------------ any tool

    /** A tool's result as its view: one projection per kind of read, so every tool has exactly one. */
    public Record project(ToolRead read, OutboundTurn turn) {
        return switch (read) {
            case ToolRead.MonthTotals r -> monthTotals(r.response(), turn);
            case ToolRead.CategoryTotals r -> categoryTotals(r.response(), turn);
            case ToolRead.AccountTotals r -> accountTotals(r.response(), turn);
            case ToolRead.TopMerchants r -> topMerchants(r.response(), turn);
            case ToolRead.Trends r -> trends(r.response(), turn);
            case ToolRead.Comparison r -> comparison(r.response(), turn);
            case ToolRead.Transactions r -> transactions(r.response(), r.filter(), r.limit(), turn);
            case ToolRead.Recurring r -> recurring(r.response(), turn);
            case ToolRead.Alerts r -> alerts(r.response(), turn);
            case ToolRead.Coverage r -> coverage(r.response(), turn);
        };
    }

    /** The data notes of a result (§3.1): flag type, account alias, month. */
    public DataNotesView dataNotes(List<DataNote> notes, OutboundTurn turn) {
        return new DataNotesView(notes.stream().map(n -> new FlagView(n.type().name(),
                turn.aliases().alias(n.accountId()), month(n.month()))).distinct().toList());
    }

    /**
     * A tool error: a code, a fixed message and the parameters at fault by their schema names. An unknown category also
     * lists the valid names, never the value the model sent.
     */
    public ToolErrorView error(String code, ToolProblem message, List<FieldProblem> fields, OutboundTurn turn) {
        return new ToolErrorView(code, message.text(), fields.stream()
                .map(f -> new ToolErrorView.Field(f.name(), f.problem().text(), f.conflictsWith(),
                        f.problem() == ToolProblem.NOT_A_CATEGORY ? CategoryNamesQuery.sorted(turn.categories())
                                : null))
                .toList());
    }

    // ------------------------------------------------------------------ get_month_totals

    public MonthTotalsView monthTotals(MonthlyTotalsResponse r, OutboundTurn turn) {
        MonthRange range = r.range();
        return new MonthTotalsView(month(range == null ? null : range.from()), month(range == null ? null : range.to()),
                turn.aliases().aliasOrNull(r.accountId()), month(r.latestMonth()), month(r.latestCompleteMonth()),
                r.months().stream().map(m -> month(m, r.accountId(), turn)).toList());
    }

    private MonthTotalsView.Month month(MonthTotals m, Long accountId, OutboundTurn turn) {
        Map<String, String> drill = params("month", m.month().toString(), "accountId", id(accountId));
        String ref = turn.refs().ref(new RefRegistry.Cell("Totals · " + m.month() + scope(accountId, turn),
                List.of(drill), monthFigures(drill)));
        return new MonthTotalsView.Month(ref, m.month().toString(), m.coverage().name(), money(m.income()),
                money(m.spending()), money(m.net()), percent(m.savingsRatePercent()), money(m.refunds().net()),
                flow(m.uncategorized()), m.dataQuality().reviewPendingCount(), money(m.unlinkedTransfers().net()),
                quality(m.dataQuality(), m.month(), turn));
    }

    /**
     * What each figure of a month rests on (phase-5.md §1.1): spending is the net of the spending rows, income of the
     * income rows, net both; the flows and counts their bucket's rows; percents and pending reviews are derived.
     */
    private static Map<String, FigureBasis> monthFigures(Map<String, String> month) {
        Map<String, String> spent = with(month, "measure", "SPENT");
        Map<String, String> received = with(month, "measure", "RECEIVED");
        Map<String, String> uncategorized = with(month, "bucket", "UNCATEGORIZED");
        Map<String, String> unlinked = with(month, "bucket", "UNLINKED_TRANSFER");
        Map<String, FigureBasis> figures = new HashMap<>();
        figures.put("income", FigureBasis.sum(NET, received));
        figures.put("spending", FigureBasis.sum(NET, spent));
        figures.put("net", FigureBasis.sumOf(NET, spent, received));
        figures.put("savingsRatePercent", FigureBasis.derived(received, spent));
        figures.put("refundsNet", FigureBasis.sum(NET, with(month, "bucket", "REFUND")));
        flowFigures(figures, "uncategorized.", uncategorized);
        figures.put("reviewPending", FigureBasis.derived(uncategorized));
        figures.put("unlinkedTransfersNet", FigureBasis.sum(NET, unlinked));
        figures.put("dataQuality.countedTxns", FigureBasis.sumOf(COUNT, spent, received));
        figures.put("dataQuality.uncategorizedCount", FigureBasis.sum(COUNT, uncategorized));
        figures.put("dataQuality.reviewPendingCount", FigureBasis.derived(uncategorized));
        figures.put("dataQuality.unlinkedTransferCount", FigureBasis.sum(COUNT, unlinked));
        figures.put("dataQuality.uncategorizedPercentOfCount", FigureBasis.derived(uncategorized, spent, received));
        figures.put("dataQuality.reviewPendingPercentOfCount", FigureBasis.derived(uncategorized, spent, received));
        figures.put("dataQuality.uncategorizedPercentOfSpending", FigureBasis.derived(uncategorized, spent));
        return figures;
    }

    private MonthTotalsView.Quality quality(DataQuality q, YearMonth month, OutboundTurn turn) {
        return new MonthTotalsView.Quality(q.countedTxns(), q.uncategorizedCount(), q.reviewPendingCount(),
                q.unlinkedTransferCount(), percent(q.uncategorizedPercentOfCount()),
                percent(q.reviewPendingPercentOfCount()), percent(q.uncategorizedPercentOfSpending()),
                flags(q.flags(), month, turn));
    }

    /** Flags of one month; an alert's flags cover the months its figures used, so they carry no month (null). */
    private static List<FlagView> flags(List<MonthFlag> flags, YearMonth month, OutboundTurn turn) {
        return flags.stream()
                .map(f -> new FlagView(f.type().name(), turn.aliases().alias(f.accountId()),
                        month == null ? null : month.toString()))
                .distinct()
                .toList();
    }

    // ------------------------------------------------------------------ get_category_totals

    public CategoryTotalsView categoryTotals(CategoryTotalsResponse r, OutboundTurn turn) {
        return new CategoryTotalsView(month(r.range().from()), month(r.range().to()),
                turn.aliases().aliasOrNull(r.accountId()),
                r.coverage().stream().map(c -> new MonthCoverageView(c.month().toString(), c.coverage().name())).toList(),
                r.rows().stream().map(row -> categoryRow(row, r.range(), r.accountId(), turn)).toList());
    }

    private CategoryTotalsView.Row categoryRow(CategoryTotalsResponse.Row row, MonthRange range, Long accountId,
                                               OutboundTurn turn) {
        String label = (row.category() != null ? row.category().name() : switch (row.bucket()) {
            case UNCATEGORIZED -> "Uncategorized";
            case LINKED_TRANSFER -> "Linked transfers";
            default -> row.bucket().name();
        }) + " · " + range.from() + "–" + range.to() + scope(accountId, turn);
        Map<String, String> drill = params("from", range.from().toString(), "to", range.to().toString(),
                "bucket", row.bucket().name(), "categoryId", row.category() == null ? null : id(row.category().id()),
                "accountId", id(accountId));
        Map<String, FigureBasis> figures = new HashMap<>();
        flowFigures(figures, "total.", drill);
        for (CategoryTotalsResponse.Cell c : row.months()) {
            Map<String, String> month = with(drill, "from", null, "to", null, "month", c.month().toString());
            flowFigures(figures, "months[" + c.month() + "].", month);
            figures.put("months[" + c.month() + "].reviewPending", FigureBasis.derived(month));
        }
        String ref = turn.refs().ref(new RefRegistry.Cell(label, List.of(drill), figures));
        return new CategoryTotalsView.Row(ref, row.bucket().name(), category(row.category()), flow(row.total()),
                row.months().stream().map(c -> new CategoryTotalsView.Cell(c.month().toString(),
                        money(c.flow().moneyOut()), money(c.flow().moneyIn()), money(c.flow().net()), c.flow().count(),
                        c.reviewPending())).toList());
    }

    // ------------------------------------------------------------------ get_account_totals

    public AccountTotalsView accountTotals(AccountTotalsResponse r, OutboundTurn turn) {
        return new AccountTotalsView(month(r.range() == null ? null : r.range().from()),
                month(r.range() == null ? null : r.range().to()), month(r.latestMonth()), month(r.latestCompleteMonth()),
                r.accounts().stream().map(a -> new AccountTotalsView.Account(turn.aliases().alias(a.accountId()),
                        a.months().stream().map(m -> month(m, a.accountId(), turn)).toList())).toList());
    }

    // ------------------------------------------------------------------ get_top_merchants

    public TopMerchantsView topMerchants(MerchantTotalsResponse r, OutboundTurn turn) {
        return new TopMerchantsView(month(r.range().from()), month(r.range().to()),
                turn.aliases().aliasOrNull(r.accountId()), categoryName(r.categoryId(), turn),
                r.items().stream().map(m -> {
                    Map<String, String> drill = params("from", r.range().from().toString(), "to",
                            r.range().to().toString(), "measure", "SPENT", "merchant", m.merchant(),
                            "noMerchant", m.merchant() == null ? "true" : null, "accountId", id(r.accountId()),
                            "categoryId", id(r.categoryId()));
                    // spent is a positive magnitude: the spending rows' net, negated
                    String ref = turn.refs().ref(new RefRegistry.Cell((m.merchant() == null ? "No merchant"
                            : turn.merchant(m.merchant())) + " · " + r.range().from() + "–" + r.range().to(),
                            List.of(drill), Map.of(
                                    "spent", FigureBasis.sum(NET, List.of(new FigureBasis.Term(-1, drill))),
                                    "count", FigureBasis.sum(COUNT, drill))));
                    return new TopMerchantsView.Merchant(ref, turn.merchant(m.merchant()), money(m.spent()),
                            m.count(), date(m.firstDate()), date(m.lastDate()), category(m.category()));
                }).toList(),
                money(r.othersSpent()), r.othersCount(), money(r.totalSpent()), r.totalCount());
    }

    // ------------------------------------------------------------------ get_trends

    public TrendsView trends(TrendsResponse r, OutboundTurn turn) {
        return new TrendsView(month(r.month()), turn.aliases().aliasOrNull(r.accountId()),
                categoryName(r.categoryId(), turn),
                r.rows().stream().map(row -> trendRow(row, r.month(), r.accountId(), turn)).toList());
    }

    private TrendsView.Row trendRow(TrendRow row, YearMonth month, Long accountId, OutboundTurn turn) {
        Map<String, String> rows = switch (row.line()) {
            case TOTAL_SPENDING -> params("measure", "SPENT", "accountId", id(accountId));
            case TOTAL_INCOME -> params("measure", "RECEIVED", "accountId", id(accountId));
            case UNCATEGORIZED -> params("bucket", "UNCATEGORIZED", "measure", "SPENT", "accountId", id(accountId));
            case CATEGORY -> params("bucket", row.bucket().name(), "categoryId", id(row.category().id()),
                    "accountId", id(accountId));
        };
        Map<String, String> drill = with(rows, "month", month.toString());
        String label = (row.category() != null ? row.category().name() : row.line().name()) + " · " + month
                + scope(accountId, turn);
        String ref = turn.refs().ref(new RefRegistry.Cell(label, List.of(drill), trendFigures(row, rows, month)));
        return new TrendsView.Row(ref, row.line().name(), row.bucket() == null ? null : row.bucket().name(),
                category(row.category()), row.measure().name(), money(row.amount()), money(row.previousMonth()),
                money(row.changeAmount()), percent(row.changePercent()), average(row.trailing3()),
                average(row.trailing6()), money(row.sameMonthLastYear()),
                row.monthCoverage() == null ? null : row.monthCoverage().name());
    }

    /**
     * What each figure of a trend row rests on (TrendsService): amounts are magnitudes, so a spending line's amount is
     * its rows' net negated; the change is this month's rows less the previous month's; percents and the trailing
     * averages are derived from their months' rows.
     */
    private static Map<String, FigureBasis> trendFigures(TrendRow row, Map<String, String> rows, YearMonth month) {
        int sign = row.measure() == TrendMeasure.SPENT ? -1 : 1;
        FigureBasis.Term at = new FigureBasis.Term(sign, with(rows, "month", month.toString()));
        FigureBasis.Term previous = new FigureBasis.Term(sign, with(rows, "month", month.minusMonths(1).toString()));
        FigureBasis.Term lastYear = new FigureBasis.Term(sign, with(rows, "month", month.minusMonths(12).toString()));
        Map<String, FigureBasis> figures = new HashMap<>();
        figures.put("amount", FigureBasis.sum(NET, List.of(at)));
        figures.put("previousMonth", FigureBasis.sum(NET, List.of(previous)));
        figures.put("changeAmount", FigureBasis.sum(NET, List.of(at, previous.negated())));
        figures.put("changePercent", FigureBasis.derived(List.of(at, previous)));
        figures.put("sameMonthLastYear", FigureBasis.sum(NET, List.of(lastYear)));
        for (int months : new int[] {3, 6}) {
            FigureBasis trailing = FigureBasis.derived(with(rows, "from", month.minusMonths(months).toString(),
                    "to", month.minusMonths(1).toString()));
            for (String field : List.of("amount", "monthsUsed", "monthsWanted")) {
                figures.put("trailing" + months + "." + field, trailing);
            }
        }
        return figures;
    }

    // ------------------------------------------------------------------ compare_periods

    public ComparisonView comparison(ComparisonResponse r, OutboundTurn turn) {
        String label = r.measure().name() + (r.category() == null ? "" : " " + r.category().name()) + " · "
                + span(r.a().range()) + " vs " + span(r.b().range()) + scope(r.accountId(), turn);
        List<FigureBasis.Term> a = sideTerms(r, r.a().range());
        List<FigureBasis.Term> b = sideTerms(r, r.b().range());
        List<FigureBasis.Term> both = new ArrayList<>(a);
        both.addAll(b);
        List<FigureBasis.Term> difference = new ArrayList<>(a);
        b.forEach(t -> difference.add(t.negated()));
        String ref = turn.refs().ref(new RefRegistry.Cell(label,
                both.stream().map(FigureBasis.Term::drillDown).toList(),
                Map.of("a.value", FigureBasis.sum(NET, a), "b.value", FigureBasis.sum(NET, b),
                        "difference", FigureBasis.sum(NET, difference),
                        "changePercent", FigureBasis.derived(both))));
        return new ComparisonView(ref, r.measure().name(), category(r.category()),
                turn.aliases().aliasOrNull(r.accountId()), side(r.a()), side(r.b()), money(r.difference()),
                percent(r.changePercent()));
    }

    /**
     * The rows one side's value is the sum of, by ComparisonService's rules: spending as a magnitude (the spending
     * rows' net, negated), income as the income rows' net, net as both nets; a category's spending from its expense
     * rows only, what it received from its income and refund rows.
     */
    private static List<FigureBasis.Term> sideTerms(ComparisonResponse r, MonthRange range) {
        Map<String, String> side = params("from", range.from().toString(), "to", range.to().toString(),
                "accountId", id(r.accountId()));
        String category = r.category() == null ? null : id(r.category().id());
        return switch (r.measure()) {
            case SPENDING -> List.of(new FigureBasis.Term(-1, with(side, "measure", "SPENT")));
            case INCOME -> List.of(new FigureBasis.Term(1, with(side, "measure", "RECEIVED")));
            case NET -> List.of(new FigureBasis.Term(1, with(side, "measure", "RECEIVED")),
                    new FigureBasis.Term(1, with(side, "measure", "SPENT")));
            case CATEGORY_SPENT -> List.of(new FigureBasis.Term(-1,
                    with(side, "bucket", "EXPENSE", "categoryId", category)));
            case CATEGORY_RECEIVED -> List.of(
                    new FigureBasis.Term(1, with(side, "bucket", "INCOME", "categoryId", category)),
                    new FigureBasis.Term(1, with(side, "bucket", "REFUND", "categoryId", category)));
        };
    }

    private static ComparisonView.Side side(ComparisonResponse.Side side) {
        return new ComparisonView.Side(side.range().from().toString(), side.range().to().toString(),
                money(side.value()), side.months().stream()
                        .map(m -> new MonthCoverageView(m.month().toString(), m.coverage().name())).toList());
    }

    // ------------------------------------------------------------------ list_transactions

    public TransactionsView transactions(DrillDownResponse r, DrillDownFilter filter, int limit, OutboundTurn turn) {
        String ref = null;
        if (r.range() != null) {
            Map<String, String> drill = with(params("bucket", filter.bucket().map(Enum::name).orElse(null),
                            "measure", filter.measure().map(Enum::name).orElse(null),
                            "accountId", id(filter.accountId().orElse(null)),
                            "categoryId", id(filter.categoryId().orElse(null)),
                            "merchant", filter.merchant().orElse(null)),
                    "month", filter.month().map(YearMonth::toString).orElse(null),
                    "from", filter.month().isPresent() ? null : r.range().from().toString(),
                    "to", filter.month().isPresent() ? null : r.range().to().toString());
            Map<String, FigureBasis> figures = new HashMap<>();
            flowFigures(figures, "total.", drill);
            ref = turn.refs().ref(new RefRegistry.Cell("Transactions · " + span(r.range())
                    + scope(filter.accountId().orElse(null), turn), List.of(drill), figures));
        }
        List<TxnView> items = r.items().stream().limit(limit)
                .map(i -> txn(i.id(), i.accountId(), i.txnDate(), i.amount(), i.merchant(), i.category(),
                        i.bucket().name(), turn))
                .toList();
        return new TransactionsView(ref, month(r.range() == null ? null : r.range().from()),
                month(r.range() == null ? null : r.range().to()), flow(r.total()), items,
                r.total().count() > items.size());
    }

    // ------------------------------------------------------------------ get_recurring_charges

    public RecurringView recurring(RecurringChargesResponse r, OutboundTurn turn) {
        return new RecurringView(turn.aliases().aliasOrNull(r.accountId()), r.status().name(),
                r.items().stream().map(s -> series(s, turn)).toList(), r.truncated());
    }

    private RecurringView.Series series(RecurringCharge s, OutboundTurn turn) {
        String ref = turn.refs().ref(new RefRegistry.Series(s.accountId(), s.merchant(),
                YearMonth.from(s.firstDate()), YearMonth.from(s.lastDate())));
        return new RecurringView.Series(ref, turn.merchant(s.merchant()), turn.aliases().alias(s.accountId()),
                category(s.category()), s.cadence().name(), money(s.typicalAmount()), money(s.lastAmount()),
                s.fixedPrice(), s.occurrences(), date(s.firstDate()), date(s.lastDate()), date(s.nextExpectedDate()),
                s.status().name());
    }

    // ------------------------------------------------------------------ get_alerts

    public AlertsView alerts(AlertListResponse r, OutboundTurn turn) {
        return new AlertsView(month(r.range().from()), month(r.range().to()),
                r.items().stream().map(a -> alert(a, turn)).toList(),
                r.counts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(e -> new AlertsView.Count(e.getKey().name(), e.getValue())).toList(),
                r.truncated());
    }

    private AlertsView.Item alert(Alert a, OutboundTurn turn) {
        String ref = turn.refs().ref(new RefRegistry.Alert(a.key(), a.type().name(), a.month(),
                a.txns().stream().limit(ALERT_TXNS).map(TxnRef::id).toList()));
        List<TxnView> txns = a.txns().stream().limit(ALERT_TXNS).map(t -> txn(t, turn)).toList();
        return new AlertsView.Item(ref, a.type().name(), a.month().toString(), basis(a.basis()),
                details(a.details(), turn), txns, flags(a.dataFlags(), null, turn), a.dismissed());
    }

    private static AlertsView.Basis basis(AlertBasis b) {
        return new AlertsView.Basis(quantity(b.observed()), quantity(b.baseline()), quantity(b.threshold()),
                date(b.periodStart()), date(b.periodEnd()),
                b.settings().stream().map(s -> new AlertsView.Setting(s.name(), quantity(s.value()))).toList());
    }

    private AlertsView.Details details(AlertDetails d, OutboundTurn turn) {
        return switch (d) {
            case AlertDetails.CategorySpike s -> new AlertsView.Details("CATEGORY_SPIKE", category(s.category()), null,
                    null, money(s.spent()), average(s.baseline()), money(s.increaseAmount()),
                    percent(s.increasePercent()), null, null, null, null, null, null, null, null, null, null, null,
                    null);
            case AlertDetails.LargeTransaction l -> new AlertsView.Details("LARGE_TRANSACTION", category(l.category()),
                    turn.merchant(l.txn().merchant()), turn.aliases().alias(l.txn().accountId()), null, null, null,
                    null, turn.refs().ref(new RefRegistry.Txn(l.txn().id())), money(l.median()), l.historyCount(),
                    ratio(l.timesMedian()), null, money(l.txn().amount()), null, null, null, null, null, null);
            case AlertDetails.PossibleDuplicate p -> new AlertsView.Details("POSSIBLE_DUPLICATE", null,
                    turn.merchant(p.merchant()), null, null, null, null, null, null, null, null, null,
                    p.reasons().stream().map(Enum::name).toList(), money(p.amount()), null, null, null, null, null,
                    null);
            case AlertDetails.RecurringPriceIncrease p -> new AlertsView.Details("RECURRING_PRICE_INCREASE", null,
                    turn.merchant(p.merchant()), turn.aliases().alias(p.accountId()), null, null,
                    money(p.increaseAmount()), percent(p.increasePercent()), null, null, null, null, null, null,
                    money(p.previousAmount()), money(p.newAmount()), null, null, null, null);
            case AlertDetails.NewRecurring n -> new AlertsView.Details("NEW_RECURRING", null,
                    turn.merchant(n.merchant()), turn.aliases().alias(n.accountId()), null, null, null, null, null,
                    null, null, null, null, null, null, null, money(n.typicalAmount()), n.occurrences(),
                    date(n.firstDate()), date(n.lastDate()));
        };
    }

    private static AlertsView.Quantity quantity(Quantity q) {
        return q == null ? null : new AlertsView.Quantity(q.unit().name(), q.value());
    }

    // ------------------------------------------------------------------ get_data_coverage

    public CoverageView coverage(CoverageResponse r, OutboundTurn turn) {
        return new CoverageView(date(r.lastCoveredDay()), month(r.latestMonth()), month(r.latestCompleteMonth()),
                r.accounts().stream().map(a -> new CoverageView.Account(turn.aliases().alias(a.accountId()),
                        date(a.firstDay()), date(a.closedOn()), spans(a.covered()), spans(a.gaps()),
                        a.flaggedStatements().stream().map(f -> new CoverageView.Flagged(f.flag().name(),
                                date(f.periodStart()), date(f.periodEnd()))).toList(), a.truncated())).toList());
    }

    private static List<CoverageView.Span> spans(List<CoverageResponse.DateSpan> spans) {
        return spans.stream().map(s -> new CoverageView.Span(date(s.start()), date(s.end()))).toList();
    }

    // ------------------------------------------------------------------ shared

    private TxnView txn(TxnRef t, OutboundTurn turn) {
        return txn(t.id(), t.accountId(), t.txnDate(), t.amount(), t.merchant(), null, null, turn);
    }

    private TxnView txn(long id, long accountId, LocalDate date, BigDecimal amount, String merchant,
                        CategoryRef category, String bucket, OutboundTurn turn) {
        return new TxnView(turn.refs().ref(new RefRegistry.Txn(id)), date(date), money(amount), turn.merchant(merchant),
                category(category), bucket, turn.aliases().alias(accountId));
    }

    private static FlowView flow(MoneyFlow f) {
        return f == null ? null : new FlowView(money(f.moneyOut()), money(f.moneyIn()), money(f.net()), f.count());
    }

    private static AverageView average(Average a) {
        return a == null ? null : new AverageView(money(a.amount()), a.monthsUsed(), a.monthsWanted());
    }

    private static String category(CategoryRef category) {
        return category == null ? null : category.name();
    }

    private static String categoryName(Long categoryId, OutboundTurn turn) {
        if (categoryId == null) {
            return null;
        }
        return turn.categories().entrySet().stream().filter(e -> e.getValue().equals(categoryId))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private static String scope(Long accountId, OutboundTurn turn) {
        return accountId == null ? "" : " · " + turn.aliases().alias(accountId);
    }

    private static String span(MonthRange range) {
        return range.from().equals(range.to()) ? range.from().toString() : range.from() + "–" + range.to();
    }

    static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    static String percent(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.UNNECESSARY).toPlainString();
    }

    static String ratio(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String date(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static String month(YearMonth month) {
        return month == null ? null : month.toString();
    }

    private static String id(Long id) {
        return id == null ? null : id.toString();
    }

    /** A cell's flow figures under a prefix: each part of the drill-down's total. */
    private static void flowFigures(Map<String, FigureBasis> figures, String prefix, Map<String, String> drill) {
        figures.put(prefix + "moneyOut", FigureBasis.sum(MONEY_OUT, drill));
        figures.put(prefix + "moneyIn", FigureBasis.sum(MONEY_IN, drill));
        figures.put(prefix + "net", FigureBasis.sum(NET, drill));
        figures.put(prefix + "count", FigureBasis.sum(COUNT, drill));
    }

    /** Drill-down parameters with some set (a null value removes the parameter); sorted, as {@link #params}. */
    private static Map<String, String> with(Map<String, String> base, String... pairs) {
        Map<String, String> params = new TreeMap<>(base);
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] == null) {
                params.remove(pairs[i]);
            } else {
                params.put(pairs[i], pairs[i + 1]);
            }
        }
        return Collections.unmodifiableMap(params);
    }

    /** Drill-down parameters, nulls left out; sorted, so equal cells get equal refs. */
    private static Map<String, String> params(String... pairs) {
        Map<String, String> params = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                params.put(pairs[i], pairs[i + 1]);
            }
        }
        return Collections.unmodifiableMap(params);
    }
}
