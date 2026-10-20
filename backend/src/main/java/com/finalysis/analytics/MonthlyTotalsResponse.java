package com.finalysis.analytics;

import java.time.YearMonth;
import java.util.List;

/**
 * {@code GET /api/analytics/months}: one {@link MonthTotals} per month of the range, oldest first, empty months
 * included.
 *
 * @param range               the months asked for, or the default; null when nothing was asked for and there are no
 *                            statements
 * @param accountId           the account the totals are for; null = every account
 * @param latestMonth         the month of the last day any statement covers; null without statements
 * @param latestCompleteMonth the latest household month that is {@code COMPLETE}; null if none
 * @param months              empty when there are no statements
 */
public record MonthlyTotalsResponse(MonthRange range, Long accountId, YearMonth latestMonth,
                                    YearMonth latestCompleteMonth, List<MonthTotals> months) {
}
