package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import java.util.List;

/**
 * {@code get_merchant_totals} (§3.2): each merchant's spending over the range (a positive amount = money out after
 * refunds) and its category (of most of its rows, as every merchant list), and their sum, computed in Java, each by its
 * ref.
 */
public record MerchantTotalsView(@Outbound(MONTH) String from, @Outbound(MONTH) String to,
                                 @Outbound(ALIAS) String account, List<Merchant> merchants, Combined combined) {

    public record Merchant(@Outbound(REF) String ref, @Outbound(MERCHANT) String merchant,
                           @Outbound(CATEGORY) String category, @Outbound(MONEY) String spent,
                           @Outbound(COUNT) Integer count) {
    }

    public record Combined(@Outbound(REF) String ref, @Outbound(MONEY) String spent, @Outbound(COUNT) Integer count) {
    }
}
