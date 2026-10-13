package com.finalysis.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** {@code finalysis.transfers.window-days} (phase-3c.md §2.2): default 5, 0–14 allowed, anything else fails startup. */
class TransferPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void defaultsToFiveDays() {
        runner.run(context -> assertThat(context.getBean(TransferProperties.class).windowDays()).isEqualTo(5));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3, 14})
    void bindsAWindowInRange(int days) {
        runner.withPropertyValues("finalysis.transfers.window-days=" + days)
                .run(context -> assertThat(context.getBean(TransferProperties.class).windowDays()).isEqualTo(days));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 15})
    void failsStartupOutsideTheRange(int days) {
        runner.withPropertyValues("finalysis.transfers.window-days=" + days)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsAnOutOfRangeWindowInCode() {
        assertThatThrownBy(() -> new TransferProperties(15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 14");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TransferProperties.class)
    static class Config {
    }
}
