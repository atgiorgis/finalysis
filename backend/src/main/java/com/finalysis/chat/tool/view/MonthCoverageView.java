package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.ENUM;
import static com.finalysis.chat.tool.view.OutboundKind.MONTH;

/** A month's coverage: {@code COMPLETE}, {@code PARTIAL} or {@code NONE}. */
public record MonthCoverageView(@Outbound(MONTH) String month, @Outbound(ENUM) String coverage) {
}
