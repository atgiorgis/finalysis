package com.finalysis.analytics;

import java.time.Instant;

/**
 * One reason a month's figure may be off.
 *
 * @param accountId   the account it concerns
 * @param statementId the statement, for the two reconciliation flags; null for {@code PARTIAL_COVERAGE}
 * @param reviewedAt  when the user accepted that statement in the review queue, or null: the user may have looked at a
 *                    failed statement already
 */
public record MonthFlag(MonthFlagType type, long accountId, Long statementId, Instant reviewedAt) {
}
