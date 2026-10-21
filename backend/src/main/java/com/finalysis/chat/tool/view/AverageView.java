package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.COUNT;
import static com.finalysis.chat.tool.view.OutboundKind.MONEY;

/** A trailing average over complete months: the amount (null when none counted) and how many months counted. */
public record AverageView(@Outbound(MONEY) String amount, @Outbound(COUNT) Integer monthsUsed,
                          @Outbound(COUNT) Integer monthsWanted) {
}
