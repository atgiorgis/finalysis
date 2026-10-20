package com.finalysis.analytics;

/**
 * How much of a month the statements cover (docs/plans/phase-5.md §1.6). A month's totals can still change while it
 * isn't {@code COMPLETE}: a later statement may add rows to it.
 */
public enum Coverage {

    /** Every account expected in the month has a statement covering every day of it. */
    COMPLETE,

    /** Some account expected in the month is missing some of its days. */
    PARTIAL,

    /** No statement of any account covers any day of the month. */
    NONE
}
