package com.finalysis.alert;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Alert settings ({@code finalysis.alerts.*}, docs/plans/phase-5.md §3.3, §6.3), one block per check. A bad value
 * fails startup. The defaults were checked against the synthetic answer key only (§11); every one is configurable
 * so real data can be tuned.
 *
 * @param maxResults the longest alert list one request may ask for (and the default), 1 to {@value #MAX_MAX_RESULTS}
 */
@ConfigurationProperties("finalysis.alerts")
public record AlertProperties(@DefaultValue("100") int maxResults, @DefaultValue CategorySpike categorySpike,
                              @DefaultValue LargeTransaction largeTransaction,
                              @DefaultValue PossibleDuplicate possibleDuplicate) {

    public static final int MAX_MAX_RESULTS = 500;

    public AlertProperties {
        if (categorySpike == null) {
            categorySpike = new CategorySpike(null, null, 3);
        }
        if (largeTransaction == null) {
            largeTransaction = new LargeTransaction(null, null, 180, 5);
        }
        if (possibleDuplicate == null) {
            possibleDuplicate = new PossibleDuplicate(1);
        }
        if (maxResults < 1 || maxResults > MAX_MAX_RESULTS) {
            throw new IllegalArgumentException("finalysis.alerts.max-results must be between 1 and " + MAX_MAX_RESULTS
                    + ", was " + maxResults);
        }
    }

    /**
     * {@code category-spike}: {@code spent(M) ≥ max(baseline × (1 + min-increase-percent / 100),
     * baseline + min-increase-amount)}, the baseline being the trailing 3-month average over at least
     * {@code min-baseline-months} complete months.
     *
     * @param minIncreasePercent 0 to 1000, at most one decimal (default 50)
     * @param minIncreaseAmount  0 to 1,000,000.00, at most two decimals (default 100.00)
     * @param minBaselineMonths  1 to 3: the baseline is a 3-month average (default 3)
     */
    public record CategorySpike(@DefaultValue("50") BigDecimal minIncreasePercent,
                                @DefaultValue("100.00") BigDecimal minIncreaseAmount,
                                @DefaultValue("3") int minBaselineMonths) {

        public CategorySpike {
            minIncreasePercent = minIncreasePercent == null ? new BigDecimal("50") : minIncreasePercent;
            minIncreaseAmount = minIncreaseAmount == null ? new BigDecimal("100.00") : minIncreaseAmount;
            requireDecimal("category-spike.min-increase-percent", minIncreasePercent, 1, new BigDecimal("1000"));
            requireDecimal("category-spike.min-increase-amount", minIncreaseAmount, 2, new BigDecimal("1000000"));
            requireRange("category-spike.min-baseline-months", minBaselineMonths, 1, 3);
        }
    }

    /**
     * {@code large-transaction}: a charge with {@code |amount| ≥ max(min-amount, times-median × median)}, the median
     * being over at least {@code min-history} charges of its category in the {@code history-days} before its day.
     *
     * @param minAmount   0.01 to 1,000,000.00, at most two decimals (default 500.00)
     * @param timesMedian 1 to 100, at most two decimals (default 3)
     * @param historyDays 1 to 3650 (default 180)
     * @param minHistory  1 to 1000 (default 5)
     */
    public record LargeTransaction(@DefaultValue("500.00") BigDecimal minAmount,
                                   @DefaultValue("3") BigDecimal timesMedian,
                                   @DefaultValue("180") int historyDays, @DefaultValue("5") int minHistory) {

        public LargeTransaction {
            minAmount = minAmount == null ? new BigDecimal("500.00") : minAmount;
            timesMedian = timesMedian == null ? new BigDecimal("3") : timesMedian;
            requireDecimal("large-transaction.min-amount", minAmount, 2, new BigDecimal("1000000"));
            if (minAmount.signum() <= 0) {
                throw new IllegalArgumentException("finalysis.alerts.large-transaction.min-amount must be positive");
            }
            requireDecimal("large-transaction.times-median", timesMedian, 2, new BigDecimal("100"));
            if (timesMedian.compareTo(BigDecimal.ONE) < 0) {
                throw new IllegalArgumentException("finalysis.alerts.large-transaction.times-median must be at least 1");
            }
            requireRange("large-transaction.history-days", historyDays, 1, 3650);
            requireRange("large-transaction.min-history", minHistory, 1, 1000);
        }
    }

    /**
     * {@code possible-duplicate}: same account, amount and merchant within {@code window-days} of each other.
     *
     * @param windowDays 0 (same day only) to 7 (default 1)
     */
    public record PossibleDuplicate(@DefaultValue("1") int windowDays) {

        public PossibleDuplicate {
            requireRange("possible-duplicate.window-days", windowDays, 0, 7);
        }
    }

    private static void requireDecimal(String name, BigDecimal value, int maxScale, BigDecimal max) {
        if (value.signum() < 0 || value.compareTo(max) > 0 || value.stripTrailingZeros().scale() > maxScale) {
            throw new IllegalArgumentException("finalysis.alerts." + name + " must be between 0 and "
                    + max.toPlainString() + " with at most " + maxScale + " decimals, was " + value.toPlainString());
        }
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    "finalysis.alerts." + name + " must be between " + min + " and " + max + ", was " + value);
        }
    }
}
