package com.finalysis.analytics;

/**
 * Which bucketed rows are spending and which are income, as SQL over a subquery that selected
 * {@link TxnBucketSql#BUCKET} as {@code bucket} and the amount as {@code amount} (docs/plans/phase-5.md §1.1): the
 * same split {@link MonthTotalsCalculator} makes of the cells, written once for queries that filter rows.
 */
final class SpendingRowsSql {

    /** EXPENSE and REFUND, whatever the sign, plus uncategorized money out. */
    static final String SPENDING = "(b.bucket IN ('EXPENSE', 'REFUND') OR (b.bucket = 'UNCATEGORIZED' AND b.amount < 0))";

    /** INCOME, whatever the sign, plus uncategorized money in (zero included). */
    static final String INCOME = "(b.bucket = 'INCOME' OR (b.bucket = 'UNCATEGORIZED' AND b.amount >= 0))";

    private SpendingRowsSql() {
    }
}
