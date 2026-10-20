package com.finalysis.alert;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

/**
 * What a check found, before the service adds the dismissal and the data flags.
 *
 * @param txns       the txns to show, at most {@value AlertQueryService#MAX_TXNS}
 * @param accountIds every account the alert touches (all its txns, not only those shown), for the {@code accountId}
 *                   filter: alerts are household-wide, and the filter only chooses which to list (§6.1)
 * @param usedFrom   first day of the figures the check used, for the data flags
 * @param usedTo     last day of them
 */
record Finding(String key, AlertType type, YearMonth month, AlertBasis basis, AlertDetails details, List<TxnRef> txns,
               Set<Long> accountIds, LocalDate usedFrom, LocalDate usedTo) {
}
