package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.ALIAS;
import static com.finalysis.chat.tool.view.OutboundKind.CATEGORY;
import static com.finalysis.chat.tool.view.OutboundKind.DATE;
import static com.finalysis.chat.tool.view.OutboundKind.ENUM;
import static com.finalysis.chat.tool.view.OutboundKind.MERCHANT;
import static com.finalysis.chat.tool.view.OutboundKind.MONEY;
import static com.finalysis.chat.tool.view.OutboundKind.REF;

/**
 * One transaction: its ref, date, signed amount, merchant (outbound text), category, bucket and account alias. No id,
 * raw description, counterparty or statement line.
 */
public record TxnView(@Outbound(REF) String ref, @Outbound(DATE) String date, @Outbound(MONEY) String amount,
                      @Outbound(MERCHANT) String merchant, @Outbound(CATEGORY) String category,
                      @Outbound(ENUM) String bucket, @Outbound(ALIAS) String account) {
}
