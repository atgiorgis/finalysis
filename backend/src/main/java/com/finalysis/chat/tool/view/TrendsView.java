package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;


import java.util.List;

/**
 * {@code get_trends} (§3.2): per row the month's amount (`spent` or `received`, positive magnitudes), the previous month,
 * the change, the trailing averages over complete months and the same month a year earlier.
 */
public record TrendsView(@Outbound(MONTH) String month, @Outbound(ALIAS) String account,
                         @Outbound(CATEGORY) String category, List<Row> rows) {

    public record Row(@Outbound(REF) String ref, @Outbound(ENUM) String line, @Outbound(ENUM) String bucket,
                      @Outbound(CATEGORY) String category, @Outbound(ENUM) String measure,
                      @Outbound(MONEY) String amount, @Outbound(MONEY) String previousMonth,
                      @Outbound(MONEY) String changeAmount, @Outbound(PERCENT) String changePercent,
                      AverageView trailing3, AverageView trailing6, @Outbound(MONEY) String sameMonthLastYear,
                      @Outbound(ENUM) String monthCoverage) {
    }
}
