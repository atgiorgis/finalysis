package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.ALIAS;
import static com.finalysis.chat.tool.view.OutboundKind.ENUM;
import static com.finalysis.chat.tool.view.OutboundKind.MONTH;

/**
 * A data-quality flag, reduced (§12.2): its type, the account's alias and the month (null on an alert's flags, which
 * cover the months its figures used); no statement id, no review time.
 */
public record FlagView(@Outbound(ENUM) String type, @Outbound(ALIAS) String account, @Outbound(MONTH) String month) {
}
