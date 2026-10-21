package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import java.util.List;

/** {@code list_transactions} (§3.2): the total over every match, the first rows, and whether more matched. */
public record TransactionsView(@Outbound(MONTH) String from, @Outbound(MONTH) String to, FlowView total,
                               List<TxnView> items, @Outbound(FLAG) Boolean truncated) {
}
