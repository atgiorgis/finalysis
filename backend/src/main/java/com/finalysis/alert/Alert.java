package com.finalysis.alert;

import com.finalysis.analytics.MonthFlag;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

/**
 * One alert (ANA-04, docs/plans/phase-5.md §5.3, §6), computed on demand. The same facts always give the same
 * {@code key}, so an alert is never shown twice and a dismissal finds it again after a re-import.
 *
 * @param month     the month it belongs to (the key's month)
 * @param basis     the numbers that triggered it (§6.6)
 * @param txns      the txns it is about, at most {@value AlertQueryService#MAX_TXNS}: for a spike, the category's
 *                  largest charges in the month
 * @param dataFlags the {@code RECONCILIATION_FAILED} and {@code PARTIAL_COVERAGE} flags of the months its figures used:
 *                  a flagged month still counts, and the flag says the figure may be off
 * @param dismissed whether the user dismissed it; {@code dismissedAt} and {@code note} are null when not
 */
public record Alert(String key, AlertType type, YearMonth month, AlertBasis basis, AlertDetails details,
                    List<TxnRef> txns, List<MonthFlag> dataFlags, boolean dismissed, Instant dismissedAt,
                    String note) {
}
