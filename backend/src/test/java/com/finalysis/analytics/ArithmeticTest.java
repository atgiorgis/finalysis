package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void aShareOfNothingIsNull() {
        assertThat(Arithmetic.percent(new BigDecimal("5.00"), new BigDecimal("0.00"))).isNull();
        assertThat(Arithmetic.percent(0, 0)).isNull();
        assertThat(Arithmetic.percent(2, 11)).isEqualTo(new BigDecimal("18.2"));
        assertThat(Arithmetic.percent(1, 11)).isEqualTo(new BigDecimal("9.1"));
    }
}
