package com.finalysis.alert;

import com.finalysis.analytics.AnalyticsProperties;
import com.finalysis.analytics.Arithmetic;
import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.MonthlySeries.Charge;
import com.finalysis.analytics.MonthlySeries.Run;
import com.finalysis.analytics.RecurringSeriesQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Recurring charge price went up (docs/plans/phase-5.md §6.3, {@code recurring-price-increase}), over the monthly series
 * of §6.4: a charge whose {@code stable-count} predecessors in its run are exactly equal (a fixed price) and which is at
 * least {@code min-increase-amount} above them.
 * <ul>
 *   <li>The threshold is {@code previous + min-increase-amount}, and the check is {@code new ≥ threshold} on those
 *       values as shown (§6.6).</li>
 *   <li>Only the first charge at the new price raises it: the next one's predecessors are no longer equal, or equal
 *       to it. Variable bills (no {@code stable-count} equal charges in a row) never qualify.</li>
 *   <li>Runs are read up to the end of the range, and a run's charges up to a month never change with later imports,
 *       so a month's alerts never look past it. They are read from {@code stable-count × max-gap-days} before the
 *       range: a charge in it and the stable charges before it are always inside, with their gaps.</li>
 * </ul>
 * Month and key: the new charge's month and fingerprint.
 */
@Component
class RecurringPriceIncreaseCheck implements AlertCheck {

    private final RecurringSeriesQuery series;
    private final int stableCount;
    private final int maxGapDays;
    private final BigDecimal minIncreaseAmount;

    RecurringPriceIncreaseCheck(RecurringSeriesQuery series, AnalyticsProperties analytics, AlertProperties properties) {
        this.series = series;
        this.stableCount = analytics.recurring().stableCount();
        this.maxGapDays = analytics.recurring().maxGapDays();
        this.minIncreaseAmount = properties.recurringPriceIncrease().minIncreaseAmount();
    }

    @Override
    public AlertType type() {
        return AlertType.RECURRING_PRICE_INCREASE;
    }

    @Override
    public List<Finding> find(MonthRange range) {
        List<Finding> findings = new ArrayList<>();
        LocalDate since = range.from().atDay(1).minusDays((long) stableCount * maxGapDays);
        for (Run run : series.runs(since, range.to().plusMonths(1).atDay(1))) {
            List<Charge> charges = run.charges();
            for (int i = stableCount; i < charges.size(); i++) {
                Charge charge = charges.get(i);
                YearMonth month = YearMonth.from(charge.date());
                if (month.isBefore(range.from())) {
                    continue;
                }
                List<Charge> stable = charges.subList(i - stableCount, i);
                BigDecimal previous = stable.getLast().amount();
                if (stable.stream().anyMatch(c -> c.amount().compareTo(previous) != 0)) {
                    continue;
                }
                BigDecimal threshold = Arithmetic.money(previous.add(minIncreaseAmount));
                if (charge.amount().compareTo(threshold) >= 0) {
                    findings.add(finding(run, stable, charge, month, threshold));
                }
            }
        }
        return findings;
    }

    private Finding finding(Run run, List<Charge> stable, Charge charge, YearMonth month, BigDecimal threshold) {
        BigDecimal previous = stable.getLast().amount();
        BigDecimal increase = charge.amount().subtract(previous);
        AlertBasis basis = new AlertBasis(Quantity.money(charge.amount()), Quantity.money(previous),
                Quantity.money(threshold), stable.getFirst().date(), charge.date(),
                List.of(new AlertSetting("stable-count", Quantity.count(stableCount)),
                        new AlertSetting("min-increase-amount", Quantity.money(minIncreaseAmount))));
        AlertDetails details = new AlertDetails.RecurringPriceIncrease(run.merchant(), run.accountId(), previous,
                charge.amount(), increase, Arithmetic.percent(increase, previous));
        return new Finding(AlertKey.of(type(), month, List.of(charge.fingerprint())), type(), month, basis, details,
                List.of(AlertTxnQuery.ref(charge)), Set.of(run.accountId()), stable.getFirst().date(), charge.date());
    }
}
