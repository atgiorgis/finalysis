package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import java.util.List;

/** {@code get_account_totals} (§3.2): each account by its alias, with the same months as {@code get_month_totals}. */
public record AccountTotalsView(@Outbound(MONTH) String from, @Outbound(MONTH) String to,
                                @Outbound(MONTH) String latestMonth, @Outbound(MONTH) String latestCompleteMonth,
                                List<Account> accounts) {

    public record Account(@Outbound(ALIAS) String account, List<MonthTotalsView.Month> months) {
    }
}
