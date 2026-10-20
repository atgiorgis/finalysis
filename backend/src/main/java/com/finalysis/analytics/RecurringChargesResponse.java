package com.finalysis.analytics;

import java.util.List;

/**
 * {@code GET /api/analytics/recurring} (ANA-07, docs/plans/phase-5.md §6.5): the monthly series themselves, not only
 * the alerts about them. Read-only and bounded, like every analytics response.
 *
 * @param accountId the account asked for; null = every account
 * @param status    which series were asked for
 * @param limit     the most items listed
 * @param items     newest {@code lastDate} first, then by account and merchant
 * @param truncated more series matched than {@code limit}
 */
public record RecurringChargesResponse(Long accountId, RecurringStatusFilter status, int limit,
                                       List<RecurringCharge> items, boolean truncated) {
}
