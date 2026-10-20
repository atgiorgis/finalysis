package com.finalysis.analytics;

/** Whether a recurring charge is still coming (docs/plans/phase-5.md §6.5). */
public enum RecurringStatus {

    /** Its last charge is at most {@code active-days} (45 by default) before its account's last covered day. */
    ACTIVE,

    /** Longer ago than that: cancelled, moved to another account, or a missed bill. */
    ENDED
}
