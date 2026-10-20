package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Percents (docs/plans/phase-5.md §4, §9.4): one decimal, {@code HALF_EVEN}, null on a zero denominator. */
class ArithmeticTest {

    @ParameterizedTest
    @CsvSource({
            // part, whole, percent
            "68.00, 1807.98, 3.8",       // §1.7's worked example
            "1313.27, 3001.25, 43.8",    // §9.1's savings rate
            "1, 8, 12.5",                // exact: no rounding
            "1, 16, 6.2",                // 6.25 → 6.2: half to even, down
            "3, 16, 18.8",               // 18.75 → 18.8: half to even, up
            "-1, 16, -6.2",              // the sign doesn't change the rule
            "-79.99, 0.01, -799900.0",
            "0, 5, 0.0"})
    void percentsRoundHalfEvenToOneDecimal(String part, String whole, String percent) {
        assertThat(Arithmetic.percent(new BigDecimal(part), new BigDecimal(whole))).isEqualTo(new BigDecimal(percent));
    }

    /** §9.4's boundary cases: averages are money, two decimals, half to even. */
    @ParameterizedTest
    @CsvSource({
            "667.485, 3, 222.50",   // 222.495 → 222.50
            "667.455, 3, 222.48",   // 222.485 → 222.48
            "200.00, 3, 66.67",
            "3537.97, 2, 1768.98",  // the fixture's total spending, January and February
            "-0.01, 2, 0.00",       // −0.005 → 0.00: half to even
            "1112.46, 5, 222.49"})  // §7: Dining's six months to 2026-09
    void averagesRoundHalfEvenToCents(String sum, int count, String average) {
        assertThat(Arithmetic.average(new BigDecimal(sum), count)).isEqualByComparingTo(new BigDecimal(average));
        assertThat(Arithmetic.average(new BigDecimal(sum), count).scale()).isEqualTo(2);
    }

    @Test
    void anAverageOfNothingIsABug() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Arithmetic.average(BigDecimal.ONE, 0));
    }

    @Test
    void aShareOfNothingIsNull() {
        assertThat(Arithmetic.percent(new BigDecimal("5.00"), new BigDecimal("0.00"))).isNull();
        assertThat(Arithmetic.percent(0, 0)).isNull();
        assertThat(Arithmetic.percent(2, 11)).isEqualTo(new BigDecimal("18.2"));
        assertThat(Arithmetic.percent(1, 11)).isEqualTo(new BigDecimal("9.1"));
    }
}
