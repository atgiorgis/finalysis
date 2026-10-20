package com.finalysis.alert;

/** The unit of a {@link Quantity}, and so how its value is written. */
public enum QuantityUnit {

    /** Two decimals, positive = money out (alerts use magnitudes, docs/plans/phase-5.md §4). */
    MONEY,

    /** One decimal. */
    PERCENT,

    /** A whole number of things (txns, months). */
    COUNT,

    /** A whole number of days. */
    DAYS,

    /** A multiple, two decimals ("3.00 times the median"). */
    RATIO
}
