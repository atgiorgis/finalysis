package com.finalysis.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Analytics settings ({@code finalysis.analytics.*}, docs/plans/phase-5.md §3.3). A bad value fails startup.
 *
 * @param maxMonths     the longest {@code from}–{@code to} range one request may ask for, 1 to {@value #MAX_MAX_MONTHS}.
 *                      It bounds every response: no list grows with the size of the history
 * @param defaultMonths the range when {@code from} is omitted, ending at {@code to}; 1 to {@code maxMonths}
 * @param merchants     the merchant list's bound
 * @param recurring     the monthly series detector (§6.4) and the recurring list's bound (§6.5)
 */
@ConfigurationProperties("finalysis.analytics")
public record AnalyticsProperties(@DefaultValue("24") int maxMonths, @DefaultValue("6") int defaultMonths,
                                  @DefaultValue Merchants merchants, @DefaultValue Recurring recurring) {

    public static final int MAX_MAX_MONTHS = 120;

    /** @param maxLimit the longest merchant list one request may ask for, 1 to {@value #MAX_MAX_LIMIT} */
    public record Merchants(@DefaultValue("50") int maxLimit) {

        public static final int MAX_MAX_LIMIT = 500;

        public Merchants {
            if (maxLimit < 1 || maxLimit > MAX_MAX_LIMIT) {
                throw new IllegalArgumentException("finalysis.analytics.merchants.max-limit must be between 1 and "
                        + MAX_MAX_LIMIT + ", was " + maxLimit);
            }
        }
    }

    /**
     * The monthly series of docs/plans/phase-5.md §6.4, shared by the recurring charges list (§6.5) and the two
     * recurring alerts (§6.3), so the list and the alerts always see the same series. Kept here rather than under
     * {@code finalysis.alerts}: analytics must not depend on the alerts.
     *
     * @param minGapDays     shortest gap between two charges of a run, 1 to 365 (default 25)
     * @param maxGapDays     longest gap, {@code min-gap-days} to 365 (default 35); a gap outside them ends the run
     * @param minOccurrences charges a run needs to be a series, 2 to 24 (default 3)
     * @param stableCount    equal charges that make a fixed price: {@code fixedPrice} needs the last
     *                       {@code stable-count} + 1 equal, a price increase the {@code stable-count} before it;
     *                       2 to 12 (default 2)
     * @param activeDays     a series is {@code ACTIVE} while its last charge is at most this many days before its
     *                       account's last covered day, 1 to 3650 (default 45)
     * @param maxLimit       the longest recurring list one request may ask for, 1 to {@value #MAX_MAX_LIMIT}
     *                       (default 100)
     */
    public record Recurring(@DefaultValue("25") int minGapDays, @DefaultValue("35") int maxGapDays,
                            @DefaultValue("3") int minOccurrences, @DefaultValue("2") int stableCount,
                            @DefaultValue("45") int activeDays, @DefaultValue("100") int maxLimit) {

        public static final int MAX_MAX_LIMIT = 500;

        public Recurring {
            requireRange("recurring.min-gap-days", minGapDays, 1, 365);
            requireRange("recurring.max-gap-days", maxGapDays, minGapDays, 365);
            requireRange("recurring.min-occurrences", minOccurrences, 2, 24);
            requireRange("recurring.stable-count", stableCount, 2, 12);
            requireRange("recurring.active-days", activeDays, 1, 3650);
            requireRange("recurring.max-limit", maxLimit, 1, MAX_MAX_LIMIT);
        }
    }

    public AnalyticsProperties {
        if (merchants == null) {
            merchants = new Merchants(50);
        }
        if (recurring == null) {
            recurring = new Recurring(25, 35, 3, 2, 45, 100);
        }
        if (maxMonths < 1 || maxMonths > MAX_MAX_MONTHS) {
            throw new IllegalArgumentException(
                    "finalysis.analytics.max-months must be between 1 and " + MAX_MAX_MONTHS + ", was " + maxMonths);
        }
        if (defaultMonths < 1 || defaultMonths > maxMonths) {
            throw new IllegalArgumentException("finalysis.analytics.default-months must be between 1 and max-months ("
                    + maxMonths + "), was " + defaultMonths);
        }
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    "finalysis.analytics." + name + " must be between " + min + " and " + max + ", was " + value);
        }
    }
}
