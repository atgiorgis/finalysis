package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

import com.finalysis.analytics.MonthlySeries.Charge;
import com.finalysis.analytics.MonthlySeries.Run;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The recurrence detector on its own (docs/plans/phase-5.md §6.4), with the default gaps (25 to 35 days). Charges are
 * written by hand; nothing touches a database.
 */
class MonthlySeriesTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 10);

    private final MonthlySeries series = new MonthlySeries(25, 35);
    private final AtomicLong ids = new AtomicLong();

    /** One gap either side of each bound: 24 and 36 days end a run, 25 and 35 continue it. */
    @ParameterizedTest
    @CsvSource({"24, 2", "25, 1", "30, 1", "35, 1", "36, 2"})
    void theGapBounds(int gap, int runs) {
        List<Run> found = series.runs(List.of(charge(1, "STREAMCO", START, "9.99"),
                charge(1, "STREAMCO", START.plusDays(gap), "9.99")));

        assertThat(found).hasSize(runs);
    }

    /** Charges several times a month never make a run; the same day is a gap of 0. */
    @Test
    void frequentChargesAreSingleRuns() {
        List<Charge> charges = new ArrayList<>();
        for (int day = 0; day < 60; day += 7) {
            charges.add(charge(1, "FIXTURE GROCER", START.plusDays(day), "80.00"));
        }
        charges.add(charge(1, "FIXTURE GROCER", START, "12.00"));

        assertThat(series.runs(charges)).hasSize(charges.size()).allSatisfy(run -> assertThat(run.size()).isOne());
    }

    /** A long gap ends a run; the next charges start a new one, which is the merchant's latest run, its series. */
    @Test
    void aLongGapStartsANewRunAndTheLatestIsTheSeries() {
        List<Charge> charges = List.of(
                charge(1, "SKYWAVE", LocalDate.of(2026, 1, 12), "79.99"),
                charge(1, "SKYWAVE", LocalDate.of(2026, 2, 12), "79.99"),
                charge(1, "SKYWAVE", LocalDate.of(2026, 3, 12), "79.99"),
                charge(1, "SKYWAVE", LocalDate.of(2026, 5, 12), "79.99"),
                charge(1, "SKYWAVE", LocalDate.of(2026, 6, 12), "79.99"));

        List<Run> runs = series.runs(charges);

        assertThat(runs).extracting(Run::size).containsExactly(3, 2);
        assertThat(series.latestRuns(charges)).singleElement().satisfies(run -> {
            assertThat(run.first().date()).isEqualTo(LocalDate.of(2026, 5, 12));
            assertThat(run.size()).isEqualTo(2);
        });
    }

    /** Runs are per account and merchant: the same merchant on two accounts, and two merchants, are apart. */
    @Test
    void runsArePerAccountAndMerchant() {
        List<Charge> charges = new ArrayList<>();
        for (int month = 0; month < 3; month++) {
            charges.add(charge(2, "NETFLIX.COM", START.plusMonths(month), "15.49"));
            charges.add(charge(1, "NETFLIX.COM", START.plusMonths(month), "15.49"));
            charges.add(charge(1, "SPOTIFY USA", START.plusMonths(month).plusDays(2), "11.99"));
        }

        List<Run> runs = series.runs(charges);

        assertThat(runs).extracting(Run::accountId, Run::merchant, Run::size).containsExactly(
                tuple(1L, "NETFLIX.COM", 3),
                tuple(1L, "SPOTIFY USA", 3),
                tuple(2L, "NETFLIX.COM", 3));
    }

    /** Calendar months of 28 to 31 days all fit: 01-31, 02-28, 03-31 is one run (28 and 31 days). */
    @Test
    void monthEndsFit() {
        assertThat(series.runs(List.of(charge(1, "RENT", LocalDate.of(2026, 1, 31), "1500.00"),
                charge(1, "RENT", LocalDate.of(2026, 2, 28), "1500.00"),
                charge(1, "RENT", LocalDate.of(2026, 3, 31), "1500.00")))).singleElement()
                .satisfies(run -> assertThat(run.size()).isEqualTo(3));
    }

    /** A run as it stood on a day is a prefix of it; on a day before its first charge, nothing. */
    @Test
    void aRunUpToADayIsAPrefix() {
        Run run = series.runs(List.of(charge(1, "STREAMCO", LocalDate.of(2026, 1, 20), "9.99"),
                charge(1, "STREAMCO", LocalDate.of(2026, 2, 20), "9.99"),
                charge(1, "STREAMCO", LocalDate.of(2026, 3, 20), "11.49"))).getFirst();

        assertThat(run.upTo(LocalDate.of(2026, 2, 28))).extracting(Charge::date)
                .containsExactly(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 2, 20));
        assertThat(run.upTo(LocalDate.of(2026, 3, 20))).hasSize(3);
        assertThat(run.upTo(LocalDate.of(2026, 1, 19))).isEmpty();
    }

    /** The median is an actual charge, the lower middle one of an even count, as {@code percentile_disc(0.5)}. */
    @Test
    void theMedianIsTheLowerMiddleCharge() {
        assertThat(Run.median(amounts("11.99", "11.99", "11.99", "12.99", "12.99", "12.99")))
                .isEqualTo(new BigDecimal("11.99"));
        assertThat(Run.median(amounts("9.99", "9.99", "9.99", "11.49"))).isEqualTo(new BigDecimal("9.99"));
        assertThat(Run.median(amounts("71.06", "67.84", "68.95"))).isEqualTo(new BigDecimal("68.95"));
        assertThat(Run.median(amounts("40.00", "10.00"))).isEqualTo(new BigDecimal("10.00"));
        assertThat(Run.median(amounts("4.99"))).isEqualTo(new BigDecimal("4.99"));
    }

    /** The input's order never matters, ties on a day included (they are ordered by amount, then fingerprint). */
    @Test
    void theInputOrderNeverMatters() {
        List<Charge> charges = new ArrayList<>();
        for (int month = 0; month < 6; month++) {
            charges.add(charge(1, "METRO POWER", START.plusMonths(month), "9" + month + ".00"));
        }
        charges.add(charge(1, "METRO POWER", START.plusMonths(2), "12.00"));
        List<Run> expected = series.runs(charges);
        Random random = new Random(20261020);
        for (int i = 0; i < 20; i++) {
            List<Charge> shuffled = new ArrayList<>(charges);
            Collections.shuffle(shuffled, random);
            assertThat(series.runs(shuffled)).isEqualTo(expected);
        }
    }

    @Test
    void theGapsMustBeOrdered() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MonthlySeries(0, 35));
        assertThatIllegalArgumentException().isThrownBy(() -> new MonthlySeries(30, 29));
        assertThat(series.runs(List.of())).isEmpty();
    }

    private List<Charge> amounts(String... amounts) {
        List<Charge> charges = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            charges.add(charge(1, "X", START.plusMonths(i), amounts[i]));
        }
        return charges;
    }

    private Charge charge(long account, String merchant, LocalDate date, String amount) {
        long id = ids.incrementAndGet();
        return new Charge(id, account, merchant, date, new BigDecimal(amount), null, "fp-" + id);
    }
}
