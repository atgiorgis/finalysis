package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import java.util.List;

/** {@code get_recurring_charges} (§3.2): monthly series; amounts are positive = money out. */
public record RecurringView(@Outbound(ALIAS) String account, @Outbound(ENUM) String status, List<Series> items,
                            @Outbound(FLAG) Boolean truncated) {

    public record Series(@Outbound(REF) String ref, @Outbound(MERCHANT) String merchant, @Outbound(ALIAS) String account,
                         @Outbound(CATEGORY) String category, @Outbound(ENUM) String cadence,
                         @Outbound(MONEY) String typicalAmount, @Outbound(MONEY) String lastAmount,
                         @Outbound(FLAG) Boolean fixedPrice, @Outbound(COUNT) Integer occurrences,
                         @Outbound(DATE) String firstDate, @Outbound(DATE) String lastDate,
                         @Outbound(DATE) String nextExpectedDate, @Outbound(ENUM) String status) {
    }
}
