package com.finalysis.analytics;

import java.time.YearMonth;
import java.util.List;

/**
 * {@code GET /api/analytics/trends} (ANA-03). Rows: total spending, the expense categories by name, uncategorized
 * money out, total income, the income categories by name, then Refunds &amp; Rebates. With {@code categoryId}, only
 * that category's rows. A category is listed when it has rows in M or any month the comparisons use (M − 12 … M).
 *
 * @param month     M: the month asked for, or the latest {@code COMPLETE} month (the latest month if none is
 *                  complete); null without statements
 * @param accountId one account's txns and coverage; null = the household
 */
public record TrendsResponse(YearMonth month, Long accountId, Long categoryId, List<TrendRow> rows) {
}
