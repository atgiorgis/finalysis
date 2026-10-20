package com.finalysis.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The recurrence detector (docs/plans/phase-5.md §6.4): plain Java over the charges one SQL query reads, because it is
 * sequence logic, not arithmetic.
 * <ul>
 *   <li>Charges are grouped by account and cleaned merchant and ordered by date (then amount and fingerprint, so the
 *       input's order never matters).</li>
 *   <li>A run is a stretch of consecutive charges each {@code min-gap-days} to {@code max-gap-days} after the one
 *       before; any other gap ends it. Charges several times a month (groceries, coffee) so never make a run.</li>
 *   <li>A run depends only on the gaps inside it, so the runs of the history up to any day are prefixes of the runs
 *       of the whole history. A check that looks only up to its month's end therefore sees the same runs as of then,
 *       whatever is imported later.</li>
 * </ul>
 * Monthly cadence only. Public for the recurring alerts (step 6), which may depend on analytics.
 */
public final class MonthlySeries {

    /**
     * One charge.
     *
     * @param amount     the magnitude, positive = money out (§4)
     * @param categoryId null when uncategorized
     * @param fingerprint the txn's ING-03 fingerprint: the alerts' keys hash it, never the id
     */
    public record Charge(long txnId, long accountId, String merchant, LocalDate date, BigDecimal amount,
                         Long categoryId, String fingerprint) {

        public Charge {
            Objects.requireNonNull(merchant, "merchant");
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(fingerprint, "fingerprint");
        }
    }

    /** A run of one account and merchant, oldest charge first; never empty. */
    public record Run(long accountId, String merchant, List<Charge> charges) {

        public Run {
            charges = List.copyOf(charges);
            if (charges.isEmpty()) {
                throw new IllegalArgumentException("a run has at least one charge");
            }
        }

        public Charge first() {
            return charges.getFirst();
        }

        public Charge last() {
            return charges.getLast();
        }

        public int size() {
            return charges.size();
        }

        /** The run as it stood on {@code day}: its charges up to that day; empty when none was. */
        public List<Charge> upTo(LocalDate day) {
            return charges.stream().filter(c -> !c.date().isAfter(day)).toList();
        }

        /**
         * The lower middle charge's amount, as {@code percentile_disc(0.5)} picks it: an actual charge, so no
         * division. Of an even count, the lower of the two middle values.
         */
        public static BigDecimal median(List<Charge> charges) {
            List<BigDecimal> sorted = charges.stream().map(Charge::amount).sorted().toList();
            return sorted.get((sorted.size() - 1) / 2);
        }
    }

    private static final Comparator<Charge> ORDER = Comparator.comparing(Charge::date)
            .thenComparing(Charge::amount)
            .thenComparing(Charge::fingerprint)
            .thenComparingLong(Charge::txnId);

    private static final Comparator<Run> RUN_ORDER = Comparator.comparingLong(Run::accountId)
            .thenComparing(Run::merchant)
            .thenComparing(run -> run.first().date());

    private final int minGapDays;
    private final int maxGapDays;

    public MonthlySeries(int minGapDays, int maxGapDays) {
        if (minGapDays < 1 || maxGapDays < minGapDays) {
            throw new IllegalArgumentException("gaps must satisfy 1 <= min <= max, were " + minGapDays + " and "
                    + maxGapDays);
        }
        this.minGapDays = minGapDays;
        this.maxGapDays = maxGapDays;
    }

    /**
     * Every run of every account and merchant, single charges included, by account, merchant and first date. Each
     * charge is in exactly one run.
     */
    public List<Run> runs(Collection<Charge> charges) {
        Map<Key, List<Charge>> byKey = new LinkedHashMap<>();
        charges.stream().sorted(ORDER)
                .forEach(c -> byKey.computeIfAbsent(new Key(c.accountId(), c.merchant()), k -> new ArrayList<>()).add(c));
        List<Run> runs = new ArrayList<>();
        byKey.forEach((key, ordered) -> {
            List<Charge> run = new ArrayList<>();
            for (Charge charge : ordered) {
                if (!run.isEmpty() && !monthlyGap(run.getLast().date(), charge.date())) {
                    runs.add(new Run(key.accountId(), key.merchant(), run));
                    run = new ArrayList<>();
                }
                run.add(charge);
            }
            runs.add(new Run(key.accountId(), key.merchant(), run));
        });
        runs.sort(RUN_ORDER);
        return List.copyOf(runs);
    }

    /**
     * Each account and merchant's latest run, its series (§6.4): an earlier run that a long gap ended is not reported
     * separately.
     */
    public List<Run> latestRuns(Collection<Charge> charges) {
        Map<Key, Run> latest = new LinkedHashMap<>();
        for (Run run : runs(charges)) {
            latest.put(new Key(run.accountId(), run.merchant()), run);
        }
        return List.copyOf(latest.values());
    }

    private boolean monthlyGap(LocalDate previous, LocalDate next) {
        long days = ChronoUnit.DAYS.between(previous, next);
        return days >= minGapDays && days <= maxGapDays;
    }

    private record Key(long accountId, String merchant) {
    }
}
