package com.finalysis.analytics;

/**
 * What {@link ComparisonService} compares (docs/plans/phase-6.md §3.3). Spending and a category's spending are positive
 * magnitudes, as in the trends ({@code spent}); income and a category's money received are what was received; net is
 * signed (income − spending).
 */
public enum ComparisonMeasure {
    /** Total spending after refunds, as the trends' total spending line: positive = money out. */
    SPENDING,
    /** Total income, uncategorized money in included, as the trends' total income line. */
    INCOME,
    /** Income − spending, signed: the months' {@code net}. */
    NET,
    /** One category's spending rows (its {@code EXPENSE} bucket), positive = money out after refunds. */
    CATEGORY_SPENT,
    /** One category's money received (its {@code INCOME} and {@code REFUND} buckets). */
    CATEGORY_RECEIVED;

    public boolean needsCategory() {
        return this == CATEGORY_SPENT || this == CATEGORY_RECEIVED;
    }
}
