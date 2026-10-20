package com.finalysis.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** {@link AlertProperties} (docs/plans/phase-5.md §3.3): the defaults, and a bad value fails startup. */
class AlertSettingsTest {

    @Test
    void theDefaultsAreThePlans() {
        AlertProperties defaults = new AlertProperties(100, null, null, null);

        assertThat(defaults.categorySpike()).isEqualTo(new AlertProperties.CategorySpike(new BigDecimal("50"),
                new BigDecimal("100.00"), 3));
        assertThat(defaults.largeTransaction()).isEqualTo(new AlertProperties.LargeTransaction(
                new BigDecimal("500.00"), new BigDecimal("3"), 180, 5));
        assertThat(defaults.possibleDuplicate().windowDays()).isEqualTo(1);
    }

    @Test
    void badValuesFailStartup() {
        assertThatThrownBy(() -> new AlertProperties(0, null, null, null)).hasMessageContaining("max-results");
        assertThatThrownBy(() -> new AlertProperties.CategorySpike(new BigDecimal("-1"), null, 3))
                .hasMessageContaining("min-increase-percent");
        assertThatThrownBy(() -> new AlertProperties.CategorySpike(new BigDecimal("50.25"), null, 3))
                .as("percents have one decimal").hasMessageContaining("min-increase-percent");
        assertThatThrownBy(() -> new AlertProperties.CategorySpike(null, new BigDecimal("0.001"), 3))
                .hasMessageContaining("min-increase-amount");
        assertThatThrownBy(() -> new AlertProperties.CategorySpike(null, null, 4))
                .hasMessageContaining("min-baseline-months");
        assertThatThrownBy(() -> new AlertProperties.LargeTransaction(new BigDecimal("0.00"), null, 180, 5))
                .hasMessageContaining("min-amount");
        assertThatThrownBy(() -> new AlertProperties.LargeTransaction(null, new BigDecimal("0.5"), 180, 5))
                .hasMessageContaining("times-median");
        assertThatThrownBy(() -> new AlertProperties.LargeTransaction(null, null, 0, 5))
                .hasMessageContaining("history-days");
        assertThatThrownBy(() -> new AlertProperties.LargeTransaction(null, null, 180, 0))
                .hasMessageContaining("min-history");
        assertThatThrownBy(() -> new AlertProperties.PossibleDuplicate(8)).hasMessageContaining("window-days");
    }
}
