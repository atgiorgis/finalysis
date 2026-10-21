package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import java.util.List;

/**
 * {@code list_transactions} (§3.2): the total over every match, by its ref (a cell whose drill-down is this call's
 * filter, so the total can be cited), the first rows, and whether more matched.
 */
public record TransactionsView(@Outbound(REF) String ref, @Outbound(MONTH) String from, @Outbound(MONTH) String to,
                               FlowView total, List<TxnView> items, @Outbound(FLAG) Boolean truncated) {
}
