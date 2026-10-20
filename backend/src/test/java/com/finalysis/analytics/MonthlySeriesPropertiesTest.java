package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.analytics.MonthlySeries.Charge;
import com.finalysis.analytics.MonthlySeries.Run;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * Properties of the recurrence detector (docs/plans/phase-5.md §6.4, §9.2), with jqwik: the gap rules, that every
 * charge is in exactly one run, that the input order never matters, and that the runs of a history cut at any day are
 * prefixes of the full runs (what lets the alerts look only up to their month).
 *
 * <p>Charges are generated as day offsets from 2026-01-01 on two accounts and two merchants, so runs, breaks and
 * same-day ties all occur.
 */
class MonthlySeriesPropertiesTest {

    private static final LocalDate EPOCH = LocalDate.of(2026, 1, 1);
    private static final MonthlySeries SERIES = new MonthlySeries(25, 35);

    @Property
    void everyGapInsideARunIsMonthlyAndEveryBreakIsNot(
            @ForAll @Size(max = 40) List<@IntRange(min = 0, max = 400) Integer> days,
            @ForAll @Size(max = 40) List<@IntRange(min = 0, max = 3) Integer> keys) {
        List<Run> runs = SERIES.runs(charges(days, keys));

        for (Run run : runs) {
            for (int i = 1; i < run.size(); i++) {
                long gap = ChronoUnit.DAYS.between(run.charges().get(i - 1).date(), run.charges().get(i).date());
                assertThat(gap).isBetween(25L, 35L);
            }
        }
        // Consecutive runs of one account and merchant are separated by a gap outside 25 to 35 days.
        for (int i = 1; i < runs.size(); i++) {
            Run before = runs.get(i - 1);
            Run after = runs.get(i);
            if (before.accountId() == after.accountId() && before.merchant().equals(after.merchant())) {
                long gap = ChronoUnit.DAYS.between(before.last().date(), after.first().date());
                assertThat(gap < 25 || gap > 35).as("gap %d", gap).isTrue();
            }
        }
    }

    @Property
    void everyChargeIsInExactlyOneRun(@ForAll @Size(max = 40) List<@IntRange(min = 0, max = 400) Integer> days,
                                      @ForAll @Size(max = 40) List<@IntRange(min = 0, max = 3) Integer> keys) {
        List<Charge> charges = charges(days, keys);

        List<Long> inRuns = SERIES.runs(charges).stream().flatMap(r -> r.charges().stream()).map(Charge::txnId)
                .sorted().toList();

        assertThat(inRuns).isEqualTo(charges.stream().map(Charge::txnId).sorted().toList());
    }

    @Property
    void theInputOrderNeverMatters(@ForAll @Size(max = 40) List<@IntRange(min = 0, max = 400) Integer> days,
                                   @ForAll @Size(max = 40) List<@IntRange(min = 0, max = 3) Integer> keys,
                                   @ForAll long seed) {
        List<Charge> charges = charges(days, keys);
        List<Charge> shuffled = new ArrayList<>(charges);
        Collections.shuffle(shuffled, new Random(seed));

        assertThat(SERIES.runs(shuffled)).isEqualTo(SERIES.runs(charges));
    }

    /** Cutting the history at any day gives, for each run, the run's charges up to that day. */
    @Property
    void runsArePrefixStable(@ForAll @Size(max = 40) List<@IntRange(min = 0, max = 400) Integer> days,
                             @ForAll @Size(max = 40) List<@IntRange(min = 0, max = 3) Integer> keys,
                             @ForAll @IntRange(min = 0, max = 400) int cut) {
        List<Charge> charges = charges(days, keys);
        LocalDate day = EPOCH.plusDays(cut);

        List<List<Long>> cutRuns = ids(SERIES.runs(charges.stream().filter(c -> !c.date().isAfter(day)).toList()));
        List<List<Long>> fullRunsUpToDay = ids(SERIES.runs(charges).stream()
                .filter(run -> !run.upTo(day).isEmpty())
                .map(run -> new Run(run.accountId(), run.merchant(), run.upTo(day)))
                .toList());

        assertThat(cutRuns).isEqualTo(fullRunsUpToDay);
    }

    private static List<List<Long>> ids(List<Run> runs) {
        return runs.stream().map(r -> r.charges().stream().map(Charge::txnId).toList()).toList();
    }

    /** Charge i on day {@code days[i]}, with key {@code keys[i]} (account and merchant), amounts from a small set. */
    private static List<Charge> charges(List<Integer> days, List<Integer> keys) {
        List<Charge> charges = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            int key = i < keys.size() ? keys.get(i) : 0;
            charges.add(new Charge(i + 1, key / 2 + 1, key % 2 == 0 ? "STREAMCO" : "CLOUDBOX",
                    EPOCH.plusDays(days.get(i)), new BigDecimal(i % 3 == 0 ? "9.99" : "11.49"), null, "fp-" + (i + 1)));
        }
        return charges;
    }
}
