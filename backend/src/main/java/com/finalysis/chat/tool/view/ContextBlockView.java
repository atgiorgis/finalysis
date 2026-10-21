package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import java.util.List;

/**
 * The context block sent with every round (§3.4): today, the data's latest months, the accounts by alias, the category
 * names and the alert types. Small, public or aliased.
 */
public record ContextBlockView(@Outbound(DATE) String today, @Outbound(MONTH) String latestMonth,
                               @Outbound(MONTH) String latestCompleteMonth, List<AccountEntry> accounts,
                               @Outbound(CATEGORY) List<String> categories, @Outbound(ENUM) List<String> alertTypes) {

    public record AccountEntry(@Outbound(ALIAS) String alias, @Outbound(ENUM) String type,
                               @Outbound(FLAG) Boolean closed) {
    }
}
