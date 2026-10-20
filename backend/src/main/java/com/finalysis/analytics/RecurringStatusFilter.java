package com.finalysis.analytics;

/** Which recurring charges {@code GET /api/analytics/recurring} lists (docs/plans/phase-5.md §5.2). */
public enum RecurringStatusFilter {

    /** {@link RecurringStatus#ACTIVE} ones only: the default, "what am I subscribed to?". */
    ACTIVE,

    /** {@link RecurringStatus#ENDED} ones only. */
    ENDED,

    /** Both. */
    ALL;

    boolean includes(RecurringStatus status) {
        return this == ALL || name().equals(status.name());
    }
}
