package com.finalysis.dashboard;

import com.finalysis.alert.AlertListResponse;
import com.finalysis.alert.AlertQueryService;
import com.finalysis.analytics.CategoryTotalsResponse;
import com.finalysis.analytics.CategoryTotalsService;
import com.finalysis.analytics.ComparisonMeasure;
import com.finalysis.analytics.ComparisonResponse;
import com.finalysis.analytics.ComparisonService;
import com.finalysis.analytics.Coverage;
import com.finalysis.analytics.CoverageService;
import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.MonthTotals;
import com.finalysis.analytics.MonthlyTotalsService;
import com.finalysis.analytics.RecurringChargesResponse;
import com.finalysis.analytics.RecurringChargesService;
import com.finalysis.analytics.RecurringStatusFilter;
import com.finalysis.analytics.TxnBucket;
import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import com.finalysis.dashboard.DashboardResponse.AlertsSummary;
import com.finalysis.dashboard.DashboardResponse.CashFlowMonth;
import com.finalysis.dashboard.DashboardResponse.Change;
import com.finalysis.dashboard.DashboardResponse.Member;
import com.finalysis.dashboard.DashboardResponse.RecurringSummary;
import com.finalysis.dashboard.DashboardResponse.Slice;
import com.finalysis.dashboard.DashboardResponse.SliceKind;
import com.finalysis.dashboard.DashboardResponse.SpendingSlices;
import com.finalysis.dashboard.DashboardResponse.VsLastMonth;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds {@link DashboardResponse} from the query services (docs/plans/phase-7.md §3.1). One read-only, repeatable-read
 * transaction, so every figure comes from the same snapshot even while an import runs.
 */
@Service
public class DashboardService {

    /** Months in the cash-flow chart, the month shown included. */
    static final int CASH_FLOW_MONTHS = 6;
    /** Months of alerts summarized, the month shown included: the alerts endpoint's default. */
    static final int ALERT_MONTHS = 3;
    static final int NEWEST_ALERTS = 3;
    /** Expense categories with their own slice; the rest go to Other. */
    static final int CATEGORY_SLICES = 6;
    static final int RECURRING_SHOWN = 6;
    /** The recurring endpoint's largest limit (ANA-07), to count the active series. */
    static final int RECURRING_COUNTED = 100;

    private static final BigDecimal HUNDRED = new BigDecimal("100.0");

    private final CoverageService coverage;
    private final MonthlyTotalsService months;
    private final CategoryTotalsService categories;
    private final ComparisonService comparison;
    private final AlertQueryService alerts;
    private final RecurringChargesService recurring;

    public DashboardService(CoverageService coverage, MonthlyTotalsService months, CategoryTotalsService categories,
                            ComparisonService comparison, AlertQueryService alerts,
                            RecurringChargesService recurring) {
        this.coverage = coverage;
        this.months = months;
        this.categories = categories;
        this.comparison = comparison;
        this.alerts = alerts;
        this.recurring = recurring;
    }

    /**
     * @param month the month shown; default the latest month with data
     * @throws InvalidRequestException for a month after the latest month with data
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardResponse dashboard(Optional<YearMonth> month) {
        Optional<YearMonth> latest = Optional.ofNullable(coverage.coverage().latestMonth());
        if (month.isPresent() && latest.isPresent() && month.get().isAfter(latest.get())) {
            throw new InvalidRequestException(List.of(new InvalidField("month",
                    "must not be after the latest month with data, " + latest.get())));
        }
        if (latest.isEmpty()) {
            // No statements: nothing to show for any month (the months endpoint lists none either).
            return new DashboardResponse(month.orElse(null), null, null, null, List.of(),
                    new SpendingSlices(BigDecimal.ZERO.setScale(2), List.of()),
                    new AlertsSummary(null, Map.of(), List.of()), new RecurringSummary(0, false, List.of()));
        }
        YearMonth m = month.orElse(latest.get());

        List<MonthTotals> window = months.months(Optional.of(m.minusMonths(CASH_FLOW_MONTHS - 1)), Optional.of(m),
                Optional.empty()).months();
        MonthTotals totals = find(window, m);
        Optional<MonthTotals> previous = window.stream().filter(t -> t.month().equals(m.minusMonths(1))).findFirst();

        return new DashboardResponse(m, totals, categorizedPercent(totals), vsLastMonth(m, totals, previous),
                window.stream().map(t -> new CashFlowMonth(t.month(), t.coverage(), t.income(), t.spending(), t.net()))
                        .toList(),
                slices(categories.categories(Optional.of(m), Optional.of(m), Optional.empty(), Optional.empty(),
                        false)),
                alertsSummary(m), recurringSummary());
    }

    private static MonthTotals find(List<MonthTotals> window, YearMonth month) {
        return window.stream().filter(t -> t.month().equals(month)).findFirst()
                .orElseThrow(() -> new IllegalStateException("the months endpoint left out " + month));
    }

    static BigDecimal categorizedPercent(MonthTotals totals) {
        BigDecimal uncategorized = totals.dataQuality().uncategorizedPercentOfCount();
        return uncategorized == null ? null : HUNDRED.subtract(uncategorized);
    }

    private VsLastMonth vsLastMonth(YearMonth month, MonthTotals totals, Optional<MonthTotals> previous) {
        if (previous.isEmpty() || previous.get().coverage() == Coverage.NONE) {
            return null;
        }
        MonthRange now = new MonthRange(month, month);
        MonthRange before = new MonthRange(month.minusMonths(1), month.minusMonths(1));
        BigDecimal rate = totals.savingsRatePercent();
        BigDecimal previousRate = previous.get().savingsRatePercent();
        return new VsLastMonth(before.from(), change(ComparisonMeasure.SPENDING, now, before),
                change(ComparisonMeasure.INCOME, now, before), change(ComparisonMeasure.NET, now, before),
                rate == null || previousRate == null ? null : rate.subtract(previousRate));
    }

    private Change change(ComparisonMeasure measure, MonthRange now, MonthRange before) {
        ComparisonResponse c = comparison.compare(measure, Optional.empty(), Optional.empty(), now, before);
        return new Change(c.b().value(), c.difference(), c.changePercent());
    }

    /**
     * The donut (§3.1): the six expense categories with the most spending, then Other (the remaining expense
     * categories, any expense category whose refunds exceed its charges, and the Refunds &amp; Rebates line), then
     * Uncategorized money out. Every spending row lands in exactly one slice, so the slices add up to spending.
     */
    static SpendingSlices slices(CategoryTotalsResponse response) {
        Comparator<CategoryTotalsResponse.Row> mostSpent = Comparator
                .comparing((CategoryTotalsResponse.Row r) -> r.total().net())
                .thenComparing(r -> r.category().name());
        List<CategoryTotalsResponse.Row> spent = response.rows().stream()
                .filter(r -> r.bucket() == TxnBucket.EXPENSE && r.total().net().signum() < 0)
                .sorted(mostSpent).toList();

        List<Slice> slices = new ArrayList<>();
        spent.stream().limit(CATEGORY_SLICES).forEach(r -> slices.add(new Slice(SliceKind.CATEGORY, r.category(),
                r.total().net(), r.spendingSharePercent(), List.of())));

        List<CategoryTotalsResponse.Row> others = new ArrayList<>(spent.stream().skip(CATEGORY_SLICES).toList());
        response.rows().stream()
                .filter(r -> (r.bucket() == TxnBucket.EXPENSE && r.total().net().signum() >= 0)
                        || r.bucket() == TxnBucket.REFUND)
                .sorted(mostSpent).forEach(others::add);
        if (!others.isEmpty()) {
            BigDecimal amount = others.stream().map(r -> r.total().net()).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean shared = others.stream().allMatch(r -> r.spendingSharePercent() != null);
            BigDecimal share = shared ? others.stream().map(CategoryTotalsResponse.Row::spendingSharePercent)
                    .reduce(BigDecimal.ZERO.setScale(1), BigDecimal::add) : null;
            slices.add(new Slice(SliceKind.OTHER, null, amount, share, others.stream()
                    .map(r -> new Member(r.category(), r.total().net(), r.spendingSharePercent())).toList()));
        }

        response.rows().stream()
                .filter(r -> r.bucket() == TxnBucket.UNCATEGORIZED && r.total().moneyOut().signum() != 0)
                .forEach(r -> slices.add(new Slice(SliceKind.UNCATEGORIZED, null, r.total().moneyOut(),
                        r.spendingSharePercent(), List.of())));

        BigDecimal spending = response.spending() == null ? BigDecimal.ZERO.setScale(2) : response.spending();
        return new SpendingSlices(spending, slices);
    }

    private AlertsSummary alertsSummary(YearMonth month) {
        AlertListResponse list = alerts.alerts(Optional.of(month.minusMonths(ALERT_MONTHS - 1)), Optional.of(month),
                Optional.empty(), Optional.empty(), false, NEWEST_ALERTS);
        return new AlertsSummary(list.range(), list.counts(), list.items());
    }

    private RecurringSummary recurringSummary() {
        RecurringChargesResponse active = recurring.recurring(Optional.empty(), RecurringStatusFilter.ACTIVE,
                RECURRING_COUNTED);
        return new RecurringSummary(active.items().size(), active.truncated(),
                active.items().stream().limit(RECURRING_SHOWN).toList());
    }
}
