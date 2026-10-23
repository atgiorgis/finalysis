package com.finalysis.dashboard;

import com.finalysis.alert.Alert;
import com.finalysis.alert.AlertType;
import com.finalysis.analytics.CategoryRef;
import com.finalysis.analytics.Coverage;
import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.MonthTotals;
import com.finalysis.analytics.RecurringCharge;
import com.finalysis.api.ApiMoney;
import com.finalysis.api.ApiPercent;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/dashboard}: everything the Overview shows for one month, in one read (docs/plans/phase-7.md §3.1).
 * Every figure is an analytics or alert figure as its own endpoint returns it, or computed here in Java from them
 * ({@code categorizedPercent}, the changes from last month, Other's slice). Money is signed as stored (GR-03).
 *
 * @param month              the month shown: the one asked for, else the latest month with data; null when there are
 *                           no statements and none was asked for
 * @param totals             the month's totals and data quality, as {@code /api/analytics/months} returns them; null
 *                           when there are no statements (then every list is empty)
 * @param categorizedPercent the share of the month's counted txns that have a category: 100 − the uncategorized share
 *                           by count, one decimal; null when nothing is counted
 * @param vsLastMonth        the changes from the month before; null when that month has no data
 * @param cashFlow           the 6 months ending at {@code month}, oldest first, months without data included
 * @param spendingSlices     where the month's spending went
 * @param alerts             the alerts of the 3 months ending at {@code month}, not dismissed
 * @param recurring          the active recurring series, newest last charge first (as of the latest data, not
 *                           {@code month})
 */
public record DashboardResponse(YearMonth month, MonthTotals totals, @ApiPercent BigDecimal categorizedPercent,
                                VsLastMonth vsLastMonth, List<CashFlowMonth> cashFlow, SpendingSlices spendingSlices,
                                AlertsSummary alerts, RecurringSummary recurring) {

    public DashboardResponse {
        cashFlow = List.copyOf(cashFlow);
    }

    /**
     * Each change is this month minus last month, from {@code ComparisonService} (the chat's {@code compare_periods}
     * rule): spending and income as positive magnitudes (a positive spending change = more spent), net signed.
     *
     * @param savingsRatePoints this month's savings rate minus last month's, in percentage points; null when either
     *                          rate is null (income ≤ 0)
     */
    public record VsLastMonth(YearMonth previousMonth, Change spending, Change income, Change net,
                              @ApiPercent BigDecimal savingsRatePoints) {
    }

    /** @param changePercent the difference over last month's magnitude, one decimal; null when last month was 0 */
    public record Change(@ApiMoney BigDecimal previous, @ApiMoney BigDecimal difference,
                         @ApiPercent BigDecimal changePercent) {
    }

    public record CashFlowMonth(YearMonth month, Coverage coverage, @ApiMoney BigDecimal income,
                                @ApiMoney BigDecimal spending, @ApiMoney BigDecimal net) {
    }

    /**
     * The month's spending split for the donut: the six largest expense categories, Other, and Uncategorized. The
     * rows' amounts add up to {@code spending} exactly.
     *
     * @param spending the month's spending (≤ 0), the same as {@code totals.spending}
     */
    public record SpendingSlices(@ApiMoney BigDecimal spending, List<Slice> rows) {

        public SpendingSlices {
            rows = List.copyOf(rows);
        }
    }

    public enum SliceKind { CATEGORY, OTHER, UNCATEGORIZED }

    /**
     * One slice.
     *
     * @param category             the category of a {@code CATEGORY} slice; null otherwise
     * @param amount               signed: a {@code CATEGORY} row's net, Other's members' nets summed, Uncategorized's
     *                             money out
     * @param spendingSharePercent the category endpoint's share for a row; for Other, its members' shares summed as
     *                             returned
     * @param members              Other's categories, largest spending first: the expense categories after the six,
     *                             any expense category with refunds above its charges, and Refunds &amp; Rebates; empty
     *                             otherwise
     */
    public record Slice(SliceKind kind, CategoryRef category, @ApiMoney BigDecimal amount,
                        @ApiPercent BigDecimal spendingSharePercent, List<Member> members) {

        public Slice {
            members = List.copyOf(members);
        }
    }

    /** One of Other's categories, with its own figures as the category endpoint returns them. */
    public record Member(CategoryRef category, @ApiMoney BigDecimal amount,
                         @ApiPercent BigDecimal spendingSharePercent) {
    }

    /**
     * @param counts per type, every type listed, before {@code newest} is cut
     * @param newest the newest alerts, at most 3
     */
    public record AlertsSummary(MonthRange range, Map<AlertType, Integer> counts, List<Alert> newest) {

        public AlertsSummary {
            // In the alert types' order, as the alerts endpoint lists them (Map.copyOf's order changes per run).
            EnumMap<AlertType, Integer> ordered = new EnumMap<>(AlertType.class);
            ordered.putAll(counts);
            counts = Collections.unmodifiableMap(ordered);
            newest = List.copyOf(newest);
        }
    }

    /**
     * @param active    how many series are active (at most the recurring endpoint's limit, {@code truncated} beyond)
     * @param items     the first 6
     */
    public record RecurringSummary(int active, boolean truncated, List<RecurringCharge> items) {

        public RecurringSummary {
            items = List.copyOf(items);
        }
    }
}
