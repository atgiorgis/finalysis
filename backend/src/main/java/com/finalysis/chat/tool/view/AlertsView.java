package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * {@code get_alerts} (§3.2): each alert by its ref (never its key), its basis with units, its details (merchants
 * outbound, accounts as aliases), up to 5 txns and its data flags.
 */
public record AlertsView(@Outbound(MONTH) String from, @Outbound(MONTH) String to, List<Item> items,
                         List<Count> counts, @Outbound(FLAG) Boolean truncated) {

    public record Item(@Outbound(REF) String ref, @Outbound(ENUM) String type, @Outbound(MONTH) String month,
                       Basis basis, Details details, List<TxnView> txns, List<FlagView> dataFlags,
                       @Outbound(FLAG) Boolean dismissed) {
    }

    /** The decision's figures; the check fired because `observed` ≥ `threshold` (phase-5.md §6.6). */
    public record Basis(Quantity observed, Quantity baseline, Quantity threshold, @Outbound(DATE) String periodStart,
                        @Outbound(DATE) String periodEnd, List<Setting> settings) {
    }

    public record Quantity(@Outbound(ENUM) String unit, @Outbound(NUMBER) String value) {
    }

    public record Setting(@Outbound(SETTING) String name, Quantity value) {
    }

    /** One type's details, flattened; only its own fields are present. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Details(@Outbound(ENUM) String type, @Outbound(CATEGORY) String category,
                          @Outbound(MERCHANT) String merchant, @Outbound(ALIAS) String account,
                          @Outbound(MONEY) String spent, AverageView baseline, @Outbound(MONEY) String increaseAmount,
                          @Outbound(PERCENT) String increasePercent, @Outbound(REF) String txn,
                          @Outbound(MONEY) String median, @Outbound(COUNT) Integer historyCount,
                          @Outbound(RATIO) String timesMedian, @Outbound(ENUM) List<String> reasons,
                          @Outbound(MONEY) String amount, @Outbound(MONEY) String previousAmount,
                          @Outbound(MONEY) String newAmount, @Outbound(MONEY) String typicalAmount,
                          @Outbound(COUNT) Integer occurrences, @Outbound(DATE) String firstDate,
                          @Outbound(DATE) String lastDate) {
    }

    public record Count(@Outbound(ENUM) String type, @Outbound(COUNT) Integer count) {
    }
}
