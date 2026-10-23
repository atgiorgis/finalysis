package com.finalysis.analytics;

import com.finalysis.api.ApiMoney;
import com.finalysis.api.ApiPercent;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * {@code GET /api/analytics/categories}: money per bucket and category, per month (ANA-01). Rows without a txn in
 * the range are left out; each row lists every month of the range, oldest first.
 *
 * @param range         the months asked for, or the default; null when nothing was asked for and there are no
 *                      statements
 * @param accountId     only this account's txns; null = every account
 * @param categoryId    only txns with this category (in whatever bucket they land); null = every category
 * @param uncategorized true: only the Uncategorized row
 * @param coverage      each month's coverage, for the same scope as the rows
 * @param spending      total spending over the range for the account scope and every category, by the month totals'
 *                      rule (expenses and refunds net, plus uncategorized money out; signed, negative = money out):
 *                      the base of each row's share, whatever {@code categoryId} filters. Null without a range
 */
public record CategoryTotalsResponse(MonthRange range, Long accountId, Long categoryId, boolean uncategorized,
                                     List<MonthCoverage> coverage, List<Row> rows, @ApiMoney BigDecimal spending) {

    /**
     * One line of the breakdown. Rows come in bucket order (income, spending, refunds, uncategorized, unlinked
     * transfers, linked transfers), then by category name.
     *
     * @param bucket   where these txns count (docs/plans/phase-5.md §1.1). {@code EXPENSE} and {@code REFUND} rows are
     *                 spending, {@code INCOME} rows income; the two transfer rows are in neither
     * @param category the category; null for {@code UNCATEGORIZED} and for {@code LINKED_TRANSFER}, which is one row
     *                 whatever the txns' categories (a link wins)
     * @param total    the row over the whole range
     * @param spendingSharePercent the row's part of {@code spending}, percent with one decimal ({@code HALF_EVEN}):
     *                 an {@code EXPENSE} or {@code REFUND} row's net, the {@code UNCATEGORIZED} row's money out, over
     *                 {@code spending}. A refund row's share is negative (it lowers spending). Null for the other rows
     *                 and when spending is zero
     */
    public record Row(TxnBucket bucket, CategoryRef category, MoneyFlow total, List<Cell> months,
                      @ApiPercent BigDecimal spendingSharePercent) {
    }

    /**
     * @param reviewPending in the {@code UNCATEGORIZED} row, its txns waiting in the review queue for a category;
     *                      null in every other row
     */
    public record Cell(YearMonth month, MoneyFlow flow, Integer reviewPending) {
    }

    public record MonthCoverage(YearMonth month, Coverage coverage) {
    }
}
