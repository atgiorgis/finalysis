package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import java.util.List;

/**
 * {@code get_top_merchants} (§3.2): merchants by `spent` (positive = money out after refunds), and the rest, so the list
 * adds up to `totalSpent`. A null merchant is the group of rows without one.
 */
public record TopMerchantsView(@Outbound(MONTH) String from, @Outbound(MONTH) String to,
                               @Outbound(ALIAS) String account, @Outbound(CATEGORY) String category,
                               List<Merchant> items, @Outbound(MONEY) String othersSpent,
                               @Outbound(COUNT) Integer othersCount, @Outbound(MONEY) String totalSpent,
                               @Outbound(COUNT) Integer totalCount) {

    public record Merchant(@Outbound(REF) String ref, @Outbound(MERCHANT) String merchant,
                           @Outbound(MONEY) String spent, @Outbound(COUNT) Integer count,
                           @Outbound(DATE) String firstDate, @Outbound(DATE) String lastDate,
                           @Outbound(CATEGORY) String category) {
    }
}
