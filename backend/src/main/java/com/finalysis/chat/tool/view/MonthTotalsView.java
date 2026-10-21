package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import java.util.List;

/**
 * {@code get_month_totals} (§3.2): per month its totals and data quality. Leaves out `netChange` (an account's balance
 * movement, §12.2) and the linked transfers.
 */
public record MonthTotalsView(@Outbound(MONTH) String from, @Outbound(MONTH) String to, @Outbound(ALIAS) String account,
                              @Outbound(MONTH) String latestMonth, @Outbound(MONTH) String latestCompleteMonth,
                              List<Month> months) {

    /** One month; spending, `refundsNet` and the flows are signed (negative = money out). */
    public record Month(@Outbound(REF) String ref, @Outbound(MONTH) String month, @Outbound(ENUM) String coverage,
                        @Outbound(MONEY) String income, @Outbound(MONEY) String spending, @Outbound(MONEY) String net,
                        @Outbound(PERCENT) String savingsRatePercent, @Outbound(MONEY) String refundsNet,
                        FlowView uncategorized, @Outbound(COUNT) Integer reviewPending,
                        @Outbound(MONEY) String unlinkedTransfersNet, Quality dataQuality) {
    }

    /** ANA-08's block; percents are null when nothing is counted. */
    public record Quality(@Outbound(COUNT) Integer countedTxns, @Outbound(COUNT) Integer uncategorizedCount,
                          @Outbound(COUNT) Integer reviewPendingCount, @Outbound(COUNT) Integer unlinkedTransferCount,
                          @Outbound(PERCENT) String uncategorizedPercentOfCount,
                          @Outbound(PERCENT) String reviewPendingPercentOfCount,
                          @Outbound(PERCENT) String uncategorizedPercentOfSpending, List<FlagView> flags) {
    }
}
