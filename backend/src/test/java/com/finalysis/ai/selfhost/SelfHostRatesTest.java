package com.finalysis.ai.selfhost;

import static com.finalysis.ai.selfhost.SelfHostFiles.TEMPLATE;
import static com.finalysis.ai.selfhost.SelfHostFiles.list;
import static com.finalysis.ai.selfhost.SelfHostFiles.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The hourly rates (docs/plans/phase-9.md §4.2): every instance type the template allows has a rate in the shipped
 * {@code application.yml}, and nothing else does, so no estimate is missing for a server {@code create.sh} can make.
 */
class SelfHostRatesTest {

    private static SelfHostProperties shipped() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application.yml",
                new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment).bind("finalysis.ai.selfhost", SelfHostProperties.class).get();
    }

    @Test
    void everyInstanceTypeTheTemplateAllowsHasAnHourlyRate() throws Exception {
        List<Object> allowed = list(map(map(SelfHostFiles.template(TEMPLATE).get("Parameters")).get("InstanceType"))
                .get("AllowedValues"));

        assertThat(shipped().hourlyRates().keySet()).containsExactlyInAnyOrderElementsOf(
                allowed.stream().map(String::valueOf).toList());
    }

    @Test
    void theShippedRatesAndTimeouts() throws Exception {
        SelfHostProperties shipped = shipped();

        assertThat(shipped.hourlyRates().get("g6.xlarge")).isEqualByComparingTo("0.8048");
        assertThat(shipped.hourlyRates().get("g5.xlarge")).isEqualByComparingTo("1.006");
        assertThat(shipped.warmUpTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(shipped.awsTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void aRateMustBePositive() {
        assertThatThrownBy(() -> new SelfHostProperties(Map.of("g6.xlarge", BigDecimal.ZERO), Duration.ofMinutes(5),
                Duration.ofSeconds(10))).hasMessageContaining("hourly-rates.g6.xlarge must be positive");
    }
}
