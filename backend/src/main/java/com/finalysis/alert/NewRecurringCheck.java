package com.finalysis.alert;

import com.finalysis.analytics.AnalyticsProperties;
import com.finalysis.analytics.CoverageService;
import com.finalysis.analytics.CoveredDays;
import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.MonthlySeries.Charge;
import com.finalysis.analytics.MonthlySeries.Run;
import com.finalysis.analytics.RecurringSeriesQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * New recurring charge (docs/plans/phase-5.md §6.3, {@code new-recurring}), over the monthly series of §6.4. A run
 * raises it in the month of the charge that brought it to {@code min-occurrences}, judged as it stood at that month's
 * end (its charges and its account's coverage up to then, so a later import never changes it):
 * <ul>
 *   <li>every charge is within {@code amount-tolerance-percent} of the run's median
 *       ({@code |charge − median| × 100 ≤ median × percent}: no division);</li>
 *   <li>its first charge came at least {@code min-prior-days} after its account's first covered day: it started while
 *       the data was being watched, not at the start of the data;</li>
 *   <li>its last charge is within {@code active-days} of the account's last covered day by then.</li>
 * </ul>
 * The basis is the count: {@code occurrences ≥ min-occurrences} (§6.6). Keyed by the run's first charge's fingerprint.
 *
 * <p>Runs are read from {@code min-occurrences × max-gap-days} before the range. A run that reaches
 * {@code min-occurrences} in the range began at most {@code (min-occurrences − 1) × max-gap-days} before it, so it is read
 * whole; a run whose first charge read is closer than {@code max-gap-days} to the window's start may have begun
 * earlier, reached {@code min-occurrences} before the range, and is skipped.
 *
 * <p>{@code min-prior-days} must be more than {@code max-gap-days}, checked here at startup because the two live in
 * different blocks: a series that began before the data can first show up a whole gap after its start (§6.3).
 */
@Component
class NewRecurringCheck implements AlertCheck {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final RecurringSeriesQuery series;
    private final CoverageService coverage;
    private final int minOccurrences;
    private final int maxGapDays;
    private final int activeDays;
    private final BigDecimal tolerancePercent;
    private final int minPriorDays;

    NewRecurringCheck(RecurringSeriesQuery series, CoverageService coverage, AnalyticsProperties analytics,
                      AlertProperties properties) {
        this.series = series;
        this.coverage = coverage;
        this.minOccurrences = analytics.recurring().minOccurrences();
        this.maxGapDays = analytics.recurring().maxGapDays();
        this.activeDays = analytics.recurring().activeDays();
        this.tolerancePercent = properties.newRecurring().amountTolerancePercent();
        this.minPriorDays = properties.newRecurring().minPriorDays();
        if (minPriorDays <= maxGapDays) {
            throw new IllegalArgumentException("finalysis.alerts.new-recurring.min-prior-days (" + minPriorDays
                    + ") must be more than finalysis.analytics.recurring.max-gap-days (" + maxGapDays
                    + "): a series that began before the data can first show up a whole gap after its start");
        }
    }

    @Override
    public AlertType type() {
        return AlertType.NEW_RECURRING;
    }

    @Override
    public List<Finding> find(MonthRange range) {
        Map<YearMonth, Map<Long, CoveredDays>> coveredByMonth = new HashMap<>();
        List<Finding> findings = new ArrayList<>();
        LocalDate since = range.from().atDay(1).minusDays((long) minOccurrences * maxGapDays);
        LocalDate wholeFrom = since.plusDays(maxGapDays);
        for (Run run : series.runs(since, range.to().plusMonths(1).atDay(1))) {
            if (run.size() < minOccurrences || run.first().date().isBefore(wholeFrom)) {
                continue;
            }
            YearMonth month = YearMonth.from(run.charges().get(minOccurrences - 1).date());
            if (month.isBefore(range.from())) {
                continue;
            }
            List<Charge> charges = run.upTo(month.atEndOfMonth());
            CoveredDays covered = coveredByMonth
                    .computeIfAbsent(month, m -> coverage.coveredDays(m.atEndOfMonth()))
                    .get(run.accountId());
            BigDecimal median = Run.median(charges);
            if (covered != null && withinTolerance(charges, median) && startedWhileWatched(charges, covered)
                    && stillActive(charges, covered)) {
                findings.add(finding(run, charges, median, month));
            }
        }
        return findings;
    }

    private boolean withinTolerance(List<Charge> charges, BigDecimal median) {
        BigDecimal allowed = median.multiply(tolerancePercent);
        return charges.stream()
                .allMatch(c -> c.amount().subtract(median).abs().multiply(HUNDRED).compareTo(allowed) <= 0);
    }

    private boolean startedWhileWatched(List<Charge> charges, CoveredDays covered) {
        return ChronoUnit.DAYS.between(covered.firstDay(), charges.getFirst().date()) >= minPriorDays;
    }

    private boolean stillActive(List<Charge> charges, CoveredDays covered) {
        return ChronoUnit.DAYS.between(charges.getLast().date(), covered.lastDay()) <= activeDays;
    }

    private Finding finding(Run run, List<Charge> charges, BigDecimal median, YearMonth month) {
        LocalDate first = charges.getFirst().date();
        LocalDate last = charges.getLast().date();
        AlertBasis basis = new AlertBasis(Quantity.count(charges.size()), null, Quantity.count(minOccurrences), first,
                last, List.of(new AlertSetting("min-occurrences", Quantity.count(minOccurrences)),
                        new AlertSetting("amount-tolerance-percent", Quantity.percent(tolerancePercent)),
                        new AlertSetting("min-prior-days", Quantity.days(minPriorDays)),
                        new AlertSetting("active-days", Quantity.days(activeDays))));
        AlertDetails details = new AlertDetails.NewRecurring(run.merchant(), run.accountId(), median, charges.size(),
                first, last);
        List<TxnRef> shown = charges.stream().limit(AlertQueryService.MAX_TXNS).map(AlertTxnQuery::ref).toList();
        return new Finding(AlertKey.of(type(), month, List.of(run.first().fingerprint())), type(), month, basis,
                details, shown, Set.of(run.accountId()), first, last);
    }
}
