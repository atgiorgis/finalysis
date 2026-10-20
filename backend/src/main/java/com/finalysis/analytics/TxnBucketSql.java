package com.finalysis.analytics;

/**
 * The one SQL definition of {@link TxnBucket} (docs/plans/phase-5.md §1.1). Every analytics query selects
 * {@link #BUCKET} over {@link #FROM}, so the counting rules exist in exactly one place.
 *
 * <p>The first matching branch wins:
 * <ol>
 *   <li>on a transfer link: {@code LINKED_TRANSFER};</li>
 *   <li>Transfer kind, not marked "not a transfer": {@code UNLINKED_TRANSFER};</li>
 *   <li>Transfer kind, marked "not a transfer" (only a USER category can be there): by sign, so it counts normally;</li>
 *   <li>{@code spending_offset} (Refunds &amp; Rebates): {@code REFUND};</li>
 *   <li>Expense kind: {@code EXPENSE}; Income kind: {@code INCOME};</li>
 *   <li>no category: {@code UNCATEGORIZED}.</li>
 * </ol>
 * A zero amount counts as money in. It changes no total, but it is counted.
 */
public final class TxnBucketSql {

    /** The txn as {@code t}, its category (if any) as {@code c}. */
    public static final String FROM = "txn t LEFT JOIN category c ON c.id = t.category_id";

    /** The bucket name of {@code t}, one of {@link TxnBucket}'s constants. */
    public static final String BUCKET = """
            CASE
                WHEN t.is_transfer THEN 'LINKED_TRANSFER'
                WHEN c.kind = 'TRANSFER' AND NOT t.transfer_excluded THEN 'UNLINKED_TRANSFER'
                WHEN c.kind = 'TRANSFER' AND t.amount < 0 THEN 'EXPENSE'
                WHEN c.kind = 'TRANSFER' THEN 'INCOME'
                WHEN c.spending_offset THEN 'REFUND'
                WHEN c.kind = 'EXPENSE' THEN 'EXPENSE'
                WHEN c.kind = 'INCOME' THEN 'INCOME'
                ELSE 'UNCATEGORIZED'
            END""";

    private TxnBucketSql() {
    }
}
