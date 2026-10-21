package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import java.util.List;

/** {@code get_category_totals} (§3.2): per bucket and category, each month's flow and the total over the range. */
public record CategoryTotalsView(@Outbound(MONTH) String from, @Outbound(MONTH) String to,
                                 @Outbound(ALIAS) String account, List<MonthCoverageView> coverage, List<Row> rows) {

    /** A row; `category` is null for Uncategorized and for the one linked-transfers row. */
    public record Row(@Outbound(REF) String ref, @Outbound(ENUM) String bucket, @Outbound(CATEGORY) String category,
                      FlowView total, List<Cell> months) {
    }

    /** One month of a row; `reviewPending` only on the Uncategorized row. */
    public record Cell(@Outbound(MONTH) String month, @Outbound(MONEY) String moneyOut, @Outbound(MONEY) String moneyIn,
                       @Outbound(MONEY) String net, @Outbound(COUNT) Integer count,
                       @Outbound(COUNT) Integer reviewPending) {
    }
}
