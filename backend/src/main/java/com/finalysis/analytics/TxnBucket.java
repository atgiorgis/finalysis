package com.finalysis.analytics;

/**
 * Where a txn counts (docs/plans/phase-5.md §1.1). Every txn is in exactly one bucket; {@link TxnBucketSql} decides
 * which, in the order of the constants' rows in the plan's table.
 */
public enum TxnBucket {

    /** On a transfer link, matched or one-sided ({@code is_transfer}). In no income or spending total (GR-04). */
    LINKED_TRANSFER,

    /** A Transfer-kind category with no link, not marked "not a transfer". In no total; shown on its own line. */
    UNLINKED_TRANSFER,

    /** A category with {@code spending_offset} (Refunds &amp; Rebates): in spending, whatever the sign, on its own line. */
    REFUND,

    /** An expense category (or a not-transfer txn's Transfer category, money out): in spending, whatever the sign. */
    EXPENSE,

    /** An income category (or a not-transfer txn's Transfer category, money in): in income, whatever the sign. */
    INCOME,

    /** No category: money out ({@code amount < 0}) in spending, otherwise in income. */
    UNCATEGORIZED
}
