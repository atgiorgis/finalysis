package com.finalysis.transfer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Transfer detection settings ({@code finalysis.transfers.*}, docs/plans/phase-3c.md §2.2).
 *
 * @param windowDays how many calendar days apart, either way, the two sides of a transfer may post.
 *                   5 covers three business days plus a weekend and a holiday; a wider window only
 *                   adds coincidental matches. A value outside 0–14 fails startup.
 */
@ConfigurationProperties("finalysis.transfers")
public record TransferProperties(@DefaultValue("5") int windowDays) {

    public static final int MAX_WINDOW_DAYS = 14;

    public TransferProperties {
        if (windowDays < 0 || windowDays > MAX_WINDOW_DAYS) {
            throw new IllegalArgumentException(
                    "finalysis.transfers.window-days must be between 0 and " + MAX_WINDOW_DAYS + ", was " + windowDays);
        }
    }
}
