package com.finalysis.alert;

import java.time.LocalDate;
import java.util.List;

/**
 * Why an alert was raised, in one shape for every check (docs/plans/phase-5.md §6.6), so the UI and the Phase 6
 * chatbot can explain it without recomputing anything (GR-01, CHT-04).
 *
 * <p>The contract: the check fires exactly when {@code observed ≥ threshold}, compared on these values as shown. The
 * threshold is computed once, from the rounded baseline, and rounded {@code HALF_EVEN} to its unit, so an explanation
 * can never contradict the decision.
 *
 * @param observed    the figure that was judged
 * @param baseline    what it was compared with; null when the check has none (a duplicate group)
 * @param threshold   the value {@code observed} had to reach
 * @param periodStart first day of what the baseline covered (for a duplicate, the group's first date)
 * @param periodEnd   last day of it
 * @param settings    the configured values that applied
 */
public record AlertBasis(Quantity observed, Quantity baseline, Quantity threshold, LocalDate periodStart,
                         LocalDate periodEnd, List<AlertSetting> settings) {
}
