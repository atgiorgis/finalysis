package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import java.util.List;

/**
 * {@code get_trends} (§3.2): per row the month's amount (`spent` or `received`, positive magnitudes), the previous month
 * and the change from it, the trailing averages over complete months with the month's difference from each, and the
 * same month a year earlier.
 *
 * <p>Step 8d: the change from the previous month and the difference from an average are named apart, and both are
 * computed in Java. In dev run 2 the model called a month-over-month change of $175.00 the change from the 3-month
 * average ({@code changeAmount} sat beside {@code trailing3}).
 */
public record TrendsView(@Outbound(MONTH) String month, @Outbound(ALIAS) String account,
                         @Outbound(CATEGORY) String category, List<Row> rows) {

    public record Row(@Outbound(REF) String ref, @Outbound(ENUM) String line, @Outbound(ENUM) String bucket,
                      @Outbound(CATEGORY) String category, @Outbound(ENUM) String measure,
                      @Outbound(MONEY) String amount, @Outbound(MONEY) String previousMonth,
                      @Outbound(MONEY) String changeFromPreviousMonth,
                      @Outbound(PERCENT) String changePercentFromPreviousMonth,
                      TrailingAverage trailing3, TrailingAverage trailing6,
                      @Outbound(MONEY) String sameMonthLastYear, @Outbound(ENUM) String monthCoverage) {
    }

    /**
     * A trailing average over complete months, and the month's amount against it.
     *
     * @param amount                the average; null when no month counted
     * @param differenceFromAverage the month's amount minus this average (positive: above it); null with the average
     */
    public record TrailingAverage(@Outbound(MONEY) String amount, @Outbound(COUNT) Integer monthsUsed,
                                  @Outbound(COUNT) Integer monthsWanted,
                                  @Outbound(MONEY) String differenceFromAverage) {
    }
}
