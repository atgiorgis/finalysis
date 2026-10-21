package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.MONTH;

/**
 * The monthly summary's {@code <records>} (ANA-05, docs/plans/phase-6.md §11.1): Phase 5 records of one month, each
 * projected exactly as its tool's view: the month's totals with data quality, its trends rows, its alerts, and the
 * active recurring charges whose last charge fell in it.
 */
public record MonthlyRecordsView(@Outbound(MONTH) String month, MonthTotalsView totals, TrendsView trends,
                                 AlertsView alerts, RecurringView recurring) {
}
