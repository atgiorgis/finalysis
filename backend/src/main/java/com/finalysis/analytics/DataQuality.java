package com.finalysis.analytics;

import com.finalysis.api.ApiPercent;
import java.math.BigDecimal;
import java.util.List;

/**
 * How far a month's figures can be trusted (ANA-08, docs/plans/phase-5.md §1.7), returned with them. Percents have
 * one decimal ({@code HALF_EVEN}) and are null when their denominator is 0.
 *
 * @param countedTxns                    txns in income or spending: the INCOME, EXPENSE, REFUND and UNCATEGORIZED
 *                                       buckets (transfers excluded)
 * @param uncategorizedCount             txns without a category among them
 * @param reviewPendingCount             of those, the ones waiting in the review queue for a category (the AI left
 *                                       them, or they were marked not a transfer)
 * @param unlinkedTransferCount          Transfer-category txns with no link, also waiting for the user (CAT-08)
 * @param uncategorizedPercentOfCount    {@code uncategorizedCount / countedTxns × 100}
 * @param reviewPendingPercentOfCount    {@code reviewPendingCount / countedTxns × 100}
 * @param uncategorizedPercentOfSpending uncategorized money out / all money out of the spending rows × 100, as
 *                                       magnitudes; refunds (money in) are not in the denominator
 * @param flags                          reconciliation and coverage flags of the month
 */
public record DataQuality(int countedTxns, int uncategorizedCount, int reviewPendingCount, int unlinkedTransferCount,
                          @ApiPercent BigDecimal uncategorizedPercentOfCount,
                          @ApiPercent BigDecimal reviewPendingPercentOfCount,
                          @ApiPercent BigDecimal uncategorizedPercentOfSpending,
                          List<MonthFlag> flags) {
}
