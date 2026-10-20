package com.finalysis.analytics;

/** What a trend row's amounts measure, as positive magnitudes (docs/plans/phase-5.md §4, decision 7). */
public enum TrendMeasure {

    /** −(net of the spending rows): positive = net money out after refunds; negative only when refunds exceed it. */
    SPENT,

    /** Net of the income rows, or of the refunds (money back): positive = money in. */
    RECEIVED
}
