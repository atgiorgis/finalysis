package com.finalysis.analytics;

import java.time.YearMonth;
import java.util.List;

/**
 * {@code GET /api/analytics/accounts}: every account's {@link MonthTotals} per month of the range, accounts by id.
 * Each month's coverage, flags and data quality are the account's own; {@code netChange} is its balance movement.
 *
 * @param range               the months asked for, or the default; null when nothing was asked for and there are no
 *                            statements
 * @param latestMonth         the month of the last day any statement covers; null without statements
 * @param latestCompleteMonth the latest household month that is {@code COMPLETE}; null if none
 */
public record AccountTotalsResponse(MonthRange range, YearMonth latestMonth, YearMonth latestCompleteMonth,
                                    List<AccountTotals> accounts) {

    /** One account; {@code months} is empty when the response's is. */
    public record AccountTotals(long accountId, String name, List<MonthTotals> months) {
    }
}
