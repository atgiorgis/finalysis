package com.finalysis.alert;

import com.finalysis.analytics.MonthRange;
import java.util.List;
import java.util.Map;

/**
 * Alerts whose month is in {@code range}, newest month first, then by type and key.
 *
 * @param range     the months checked; null without statements (nothing to check)
 * @param counts    how many alerts of each type matched the filters, before {@code limit}; every type is listed
 * @param truncated whether more matched than {@code limit}
 */
public record AlertListResponse(MonthRange range, Long accountId, AlertType type, boolean includeDismissed, int limit,
                                List<Alert> items, Map<AlertType, Integer> counts, boolean truncated) {
}
