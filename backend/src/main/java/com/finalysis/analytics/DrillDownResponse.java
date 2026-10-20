package com.finalysis.analytics;

import com.finalysis.api.ApiMoney;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /api/analytics/transactions}: the txns behind a total, newest {@code txn_date} first (then by id), one
 * page at a time. {@code total} is over every matching txn, not only this page, so it equals the cell it drills into.
 *
 * @param range      the months listed; null when nothing was asked for and there are no statements
 * @param total      every matching txn, signed as stored
 * @param totalPages pages of {@code size} needed for {@code total.count()} txns
 */
public record DrillDownResponse(MonthRange range, MoneyFlow total, int page, int size, int totalPages,
                                List<Item> items) {

    /**
     * One txn. No raw description, counterparty or account number: only the cleaned merchant (the plan's
     * {@code TxnRef} rule, docs/plans/phase-5.md §5.3); its detail is {@code GET /api/transactions/{id}}.
     *
     * @param amount   signed as stored (GR-03)
     * @param category null when uncategorized
     * @param bucket   where it counts, by {@link TxnBucketSql#BUCKET}: the same rule as every total
     */
    public record Item(long id, long accountId, LocalDate txnDate, @ApiMoney BigDecimal amount, String merchant,
                       CategoryRef category, TxnBucket bucket) {
    }
}
