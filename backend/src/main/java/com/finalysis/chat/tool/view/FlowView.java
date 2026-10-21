package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.COUNT;
import static com.finalysis.chat.tool.view.OutboundKind.MONEY;

/** Money out, money in, their net and the row count (signed: negative = money out). */
public record FlowView(@Outbound(MONEY) String moneyOut, @Outbound(MONEY) String moneyIn, @Outbound(MONEY) String net,
                       @Outbound(COUNT) Integer count) {
}
