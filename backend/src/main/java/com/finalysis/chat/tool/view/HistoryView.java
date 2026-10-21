package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import java.util.List;

/**
 * What the summary call receives (CHT-06, docs/plans/phase-6.md §7.2): the previous summary, if any, and the turns being
 * folded, each its outbound question and its answer as shown with every figure replaced. Nothing in it was not sent
 * before; the figures are not even that.
 */
public record HistoryView(@Outbound(HISTORY) String summary, List<Turn> turns) {

    public record Turn(@Outbound(TEXT) String question, @Outbound(HISTORY) String answer) {
    }
}
