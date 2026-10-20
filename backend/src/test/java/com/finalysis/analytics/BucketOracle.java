package com.finalysis.analytics;

import java.math.BigDecimal;
import java.util.List;

/**
 * docs/plans/phase-5.md §1.1 written a second way, in plain Java, so tests can check {@link TxnBucketSql} and the
 * totals built on it against an implementation that shares none of their code. It reads the plan's table top to
 * bottom; keep it that literal.
 */
final class BucketOracle {

    /** A category's kind as stored, or null for no category. */
    enum Kind { EXPENSE, INCOME, TRANSFER }

    /**
     * The columns that decide a bucket.
     *
     * @param linked         {@code is_transfer}: on a transfer link
     * @param kind           the category's kind, null when uncategorized
     * @param spendingOffset {@code category.spending_offset}
     * @param excluded       {@code transfer_excluded}: marked "not a transfer"
     */
    record Row(boolean linked, Kind kind, boolean spendingOffset, boolean excluded, BigDecimal amount) {

        Row {
            if (linked && excluded) {
                throw new IllegalArgumentException("ck_txn_transfer_excluded: a linked txn can't be excluded");
            }
            if (kind == null && spendingOffset) {
                throw new IllegalArgumentException("spending_offset is a category column");
            }
        }

        boolean moneyOut() {
            return amount.signum() < 0;
        }
    }

    /**
     * Month totals as §1.1 defines them.
     *
     * @param income    INCOME plus uncategorized money in
     * @param spending  EXPENSE and REFUND plus uncategorized money out
     * @param refunds   the REFUND bucket alone (already in {@code spending})
     * @param unlinked  net of UNLINKED_TRANSFER, in no total
     * @param linked    net of LINKED_TRANSFER, in no total
     * @param sum       the plain sum of every amount
     */
    record Totals(BigDecimal income, BigDecimal spending, BigDecimal refunds, BigDecimal unlinked, BigDecimal linked,
                  BigDecimal sum) {
    }

    private BucketOracle() {
    }

    static TxnBucket bucket(Row row) {
        if (row.linked()) {
            return TxnBucket.LINKED_TRANSFER;
        }
        if (row.kind() == Kind.TRANSFER && !row.excluded()) {
            return TxnBucket.UNLINKED_TRANSFER;
        }
        if (row.kind() == Kind.TRANSFER) {
            return row.moneyOut() ? TxnBucket.EXPENSE : TxnBucket.INCOME;
        }
        if (row.spendingOffset()) {
            return TxnBucket.REFUND;
        }
        if (row.kind() == Kind.EXPENSE) {
            return TxnBucket.EXPENSE;
        }
        if (row.kind() == Kind.INCOME) {
            return TxnBucket.INCOME;
        }
        return TxnBucket.UNCATEGORIZED;
    }

    static Totals totals(List<Row> rows) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal spending = BigDecimal.ZERO;
        BigDecimal refunds = BigDecimal.ZERO;
        BigDecimal unlinked = BigDecimal.ZERO;
        BigDecimal linked = BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (Row row : rows) {
            BigDecimal amount = row.amount();
            sum = sum.add(amount);
            switch (bucket(row)) {
                case LINKED_TRANSFER -> linked = linked.add(amount);
                case UNLINKED_TRANSFER -> unlinked = unlinked.add(amount);
                case REFUND -> {
                    refunds = refunds.add(amount);
                    spending = spending.add(amount);
                }
                case EXPENSE -> spending = spending.add(amount);
                case INCOME -> income = income.add(amount);
                case UNCATEGORIZED -> {
                    if (row.moneyOut()) {
                        spending = spending.add(amount);
                    } else {
                        income = income.add(amount);
                    }
                }
            }
        }
        return new Totals(income, spending, refunds, unlinked, linked, sum);
    }
}
