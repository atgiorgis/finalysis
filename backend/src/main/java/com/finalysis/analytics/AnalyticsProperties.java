package com.finalysis.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Analytics settings ({@code finalysis.analytics.*}, docs/plans/phase-5.md §3.3). A bad value fails startup.
 *
 * @param maxMonths     the longest {@code from}–{@code to} range one request may ask for, 1 to {@value #MAX_MAX_MONTHS}.
 *                      It bounds every response: no list grows with the size of the history
 * @param defaultMonths the range when {@code from} is omitted, ending at {@code to}; 1 to {@code maxMonths}
 */
@ConfigurationProperties("finalysis.analytics")
public record AnalyticsProperties(@DefaultValue("24") int maxMonths, @DefaultValue("6") int defaultMonths) {

    public static final int MAX_MAX_MONTHS = 120;

    public AnalyticsProperties {
        if (maxMonths < 1 || maxMonths > MAX_MAX_MONTHS) {
            throw new IllegalArgumentException(
                    "finalysis.analytics.max-months must be between 1 and " + MAX_MAX_MONTHS + ", was " + maxMonths);
        }
        if (defaultMonths < 1 || defaultMonths > maxMonths) {
            throw new IllegalArgumentException("finalysis.analytics.default-months must be between 1 and max-months ("
                    + maxMonths + "), was " + defaultMonths);
        }
    }
}
