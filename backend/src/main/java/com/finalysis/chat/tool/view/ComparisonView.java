package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import java.util.List;

/**
 * {@code compare_periods} (§3.3): two periods' values and the difference and percent change, computed in Java. Spending
 * measures are positive magnitudes, net is signed; `changePercent` is null when side b is 0.
 *
 * @param higherPeriod the label of the period with the higher value ({@code 2026-03}, {@code 2026-Q3},
 *                     {@code 2026-01 to 2026-04}), or {@code SAME}; from the sign of {@code difference}, in Java
 *                     (step 8d: the model, left to map a and b back to months, named the wrong month as higher)
 */
public record ComparisonView(@Outbound(REF) String ref, @Outbound(ENUM) String measure,
                             @Outbound(CATEGORY) String category, @Outbound(ALIAS) String account, Side a, Side b,
                             @Outbound(MONEY) String difference, @Outbound(PERIOD) String higherPeriod,
                             @Outbound(PERCENT) String changePercent) {

    public record Side(@Outbound(MONTH) String from, @Outbound(MONTH) String to, @Outbound(MONEY) String value,
                       List<MonthCoverageView> months) {
    }
}
