package com.finalysis.ai.selfhost;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The Self-hosted server's settings ({@code finalysis.ai.selfhost.*}, docs/plans/phase-9.md §4.2, §5.5).
 *
 * @param hourlyRates   dollars per hour per instance type, entered by the owner from the EC2 pricing page; every type
 *                      the template allows has one ({@code SelfHostRatesTest})
 * @param warmUpTimeout how long the one model load after Start may take
 * @param awsTimeout    one EC2 call's limit, retries included
 */
@ConfigurationProperties("finalysis.ai.selfhost")
public record SelfHostProperties(@DefaultValue Map<String, BigDecimal> hourlyRates,
                                 @DefaultValue("300s") Duration warmUpTimeout,
                                 @DefaultValue("10s") Duration awsTimeout) {

    public SelfHostProperties {
        hourlyRates = Map.copyOf(hourlyRates);
        hourlyRates.forEach((type, rate) -> {
            if (rate == null || rate.signum() <= 0) {
                throw new IllegalArgumentException("finalysis.ai.selfhost.hourly-rates." + type + " must be positive");
            }
        });
        for (Duration timeout : new Duration[] {warmUpTimeout, awsTimeout}) {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("finalysis.ai.selfhost timeouts must be positive");
            }
        }
    }
}
