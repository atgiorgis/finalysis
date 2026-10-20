package com.finalysis.analytics;

/** How often a recurring charge comes (docs/plans/phase-5.md §6.4). Monthly only in Phase 5. */
public enum Cadence {

    /** Each charge {@code min-gap-days} to {@code max-gap-days} (25 to 35 by default) after the one before. */
    MONTHLY
}
