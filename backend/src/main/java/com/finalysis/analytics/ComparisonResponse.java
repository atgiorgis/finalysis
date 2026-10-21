package com.finalysis.analytics;

import com.finalysis.api.ApiMoney;
import com.finalysis.api.ApiPercent;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * Two periods compared, every figure computed in Java (docs/plans/phase-6.md §3.3; GR-01): what chat quotes when a
 * question needs a difference or a percent change.
 *
 * @param category      the category compared, or null for a household or account measure
 * @param accountId     one account's txns, or null for the household
 * @param difference    {@code a.value − b.value}, scale 2
 * @param changePercent {@code difference / |b.value| × 100}, one decimal, {@code HALF_EVEN}; null when {@code b.value}
 *                      is 0
 */
public record ComparisonResponse(ComparisonMeasure measure, CategoryRef category, Long accountId, Side a, Side b,
                                 @ApiMoney BigDecimal difference, @ApiPercent BigDecimal changePercent) {

    /**
     * One side: a month or a range of months.
     *
     * @param value  the measure over the range (units as {@link ComparisonMeasure} states)
     * @param months each month's coverage, so a partial month isn't compared silently
     */
    public record Side(MonthRange range, @ApiMoney BigDecimal value, List<MonthCoverage> months) {
    }

    /** A month's coverage, for the household or the account compared. */
    public record MonthCoverage(YearMonth month, Coverage coverage) {
    }
}
