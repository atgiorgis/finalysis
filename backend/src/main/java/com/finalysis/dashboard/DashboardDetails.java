package com.finalysis.dashboard;

import com.finalysis.analytics.CategoryRef;
import com.finalysis.analytics.Coverage;
import com.finalysis.analytics.MerchantTotalsResponse;
import com.finalysis.analytics.MoneyFlow;
import com.finalysis.analytics.TxnBucket;
import com.finalysis.api.ApiMoney;
import com.finalysis.api.ApiPercent;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/dashboard/details}: the details panel of one total of the Overview (docs/plans/phase-7.md §3.2), a
 * spending category, Uncategorized money out or the month's income. Every figure is an analytics figure or computed
 * here in Java from them; {@code drillDown} lists the rows behind {@code total} (UI-04).
 *
 * @param kind                 what the panel is about
 * @param category             the category of a {@code CATEGORY} panel; null otherwise, and for a category with no
 *                             rows in the 12 months to {@code month}
 * @param bucket               where its rows count: {@code EXPENSE}, or {@code REFUND} for the Refunds &amp; Rebates
 *                             line; {@code UNCATEGORIZED}; {@code INCOME} for the income panel (with uncategorized
 *                             money in, as the month's income)
 * @param total                signed as stored, the figure the Overview showed: a category's net, Uncategorized money
 *                             out, the month's income
 * @param spendingSharePercent the category endpoint's share of spending; null for income
 * @param amount               {@code total} as the trends measure it: a positive magnitude (spent, or received)
 * @param average              the trailing 3-month average over complete months, and the month against it
 * @param trend                the 6 months ending at {@code month}, oldest first, as magnitudes like {@code amount}
 * @param topMerchants         the category's top 5 merchants and the rest; null for Uncategorized and income
 * @param drillDown            the rows behind {@code total}
 */
public record DashboardDetails(YearMonth month, Coverage coverage, DetailKind kind, CategoryRef category,
                               TxnBucket bucket, @ApiMoney BigDecimal total, @ApiPercent BigDecimal spendingSharePercent,
                               @ApiMoney BigDecimal amount, AgainstAverage average, List<TrendCell> trend,
                               MerchantTotalsResponse topMerchants, DrillDown drillDown) {

    public DashboardDetails {
        trend = List.copyOf(trend);
    }

    public enum DetailKind { CATEGORY, UNCATEGORIZED, INCOME }

    /**
     * The trends' trailing 3-month average and the month against it, subtracted and divided in Java.
     *
     * @param amount               null when none of the three months before is complete
     * @param differenceFromAverage {@code amount} of the month − the average; null with the average
     * @param differencePercent    the difference over the average, one decimal, {@code HALF_EVEN}; null when the
     *                             average is null or 0
     */
    public record AgainstAverage(@ApiMoney BigDecimal amount, int monthsUsed, int monthsWanted,
                                 @ApiMoney BigDecimal differenceFromAverage,
                                 @ApiPercent BigDecimal differencePercent) {
    }

    /** One month of the trend; a month that isn't complete is drawn hollow and left out of the average. */
    public record TrendCell(YearMonth month, Coverage coverage, @ApiMoney BigDecimal amount) {
    }

    /**
     * {@code GET /api/analytics/transactions} with exactly these parameters lists the rows; {@code total} is over all of
     * them (the same shape as a citation's drill-down).
     */
    public record DrillDown(String path, Map<String, String> params, MoneyFlow total) {

        public static final String PATH = "/api/analytics/transactions";

        public DrillDown {
            params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }
    }
}
