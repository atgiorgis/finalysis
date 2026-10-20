package com.finalysis.analytics;

/** Which line of the trends a row is, so the rows without a category can be told apart. */
public enum TrendLine {

    /** One category in one bucket: an expense category (SPENT), an income category or Refunds &amp; Rebates (RECEIVED). */
    CATEGORY,

    /** Uncategorized money out (SPENT). */
    UNCATEGORIZED,

    /** All spending after refunds: −(the month's {@code spending}) (SPENT). */
    TOTAL_SPENDING,

    /** All income: the month's {@code income} (RECEIVED). */
    TOTAL_INCOME
}
