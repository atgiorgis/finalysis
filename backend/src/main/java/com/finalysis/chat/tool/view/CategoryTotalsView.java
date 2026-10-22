package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import java.util.List;

/**
 * {@code get_category_totals} (§3.2): per bucket and category, each month's flow and the total over the range; the
 * range's total spending over every category, and each spending row's share of it (step 8b), both computed in Java.
 */
public record CategoryTotalsView(@Outbound(MONTH) String from, @Outbound(MONTH) String to,
                                 @Outbound(ALIAS) String account, List<MonthCoverageView> coverage, Spending spending,
                                 List<Row> rows) {

    /** Total spending over the range, every category (signed, negative = money out), by its own ref. */
    public record Spending(@Outbound(REF) String ref, @Outbound(MONEY) String amount) {
    }

    /**
     * A row; `category` is null for Uncategorized and for the one linked-transfers row. `spendingSharePercent` is the
     * row's part of `spending` (expense, refund and uncategorized rows only; a refund's is negative).
     */
    public record Row(@Outbound(REF) String ref, @Outbound(ENUM) String bucket, @Outbound(CATEGORY) String category,
                      FlowView total, @Outbound(PERCENT) String spendingSharePercent, List<Cell> months) {
    }

    /** One month of a row; `reviewPending` only on the Uncategorized row. */
    public record Cell(@Outbound(MONTH) String month, @Outbound(MONEY) String moneyOut, @Outbound(MONEY) String moneyIn,
                       @Outbound(MONEY) String net, @Outbound(COUNT) Integer count,
                       @Outbound(COUNT) Integer reviewPending) {
    }
}
