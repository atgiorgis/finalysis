package com.finalysis.alert;

import com.finalysis.analytics.MonthRange;
import java.util.List;

/**
 * One rule-based check (docs/plans/phase-5.md §6.3). It only reads, runs inside the caller's read-only transaction,
 * ignores transfers, and never looks at a month after the alert's month (the duplicate check aside: a group's later
 * members can be up to {@code window-days} past it).
 */
interface AlertCheck {

    AlertType type();

    /** Every alert of this type whose month is in {@code range}. */
    List<Finding> find(MonthRange range);
}
