package com.finalysis.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "Now" for code that needs today or the time of day (chat's context block and its daily cost cap,
 * docs/plans/phase-6.md §3.4, §8): the system clock in the server's zone. Tests and the chat evaluation replace it with
 * a pinned clock, so the sample data's dates can be "today".
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
