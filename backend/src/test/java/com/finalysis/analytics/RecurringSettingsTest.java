package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@code finalysis.analytics.recurring} (docs/plans/phase-5.md §6.4, §6.5): the plan's defaults; a bad value fails startup. */
class RecurringSettingsTest {

    @Test
    void theDefaultsAreThePlans() {
        assertThat(new AnalyticsProperties(24, 6, null, null).recurring())
                .isEqualTo(new AnalyticsProperties.Recurring(25, 35, 3, 2, 45, 100));
    }

    @Test
    void badValuesFailStartup() {
        assertThatThrownBy(() -> new AnalyticsProperties.Recurring(0, 35, 3, 2, 45, 100))
                .hasMessageContaining("min-gap-days");
        assertThatThrownBy(() -> new AnalyticsProperties.Recurring(25, 24, 3, 2, 45, 100))
                .as("the longest gap can't be shorter than the shortest").hasMessageContaining("max-gap-days");
        assertThatThrownBy(() -> new AnalyticsProperties.Recurring(25, 35, 1, 2, 45, 100))
                .hasMessageContaining("min-occurrences");
        assertThatThrownBy(() -> new AnalyticsProperties.Recurring(25, 35, 3, 1, 45, 100))
                .as("one charge is always equal to itself, so every variable bill would be a fixed price")
                .hasMessageContaining("stable-count");
        assertThatThrownBy(() -> new AnalyticsProperties.Recurring(25, 35, 3, 2, 0, 100))
                .hasMessageContaining("active-days");
        assertThatThrownBy(() -> new AnalyticsProperties.Recurring(25, 35, 3, 2, 45, 501))
                .hasMessageContaining("max-limit");
        assertThat(new AnalyticsProperties.Recurring(30, 30, 2, 12, 1, 1).maxGapDays()).isEqualTo(30);
    }
}
