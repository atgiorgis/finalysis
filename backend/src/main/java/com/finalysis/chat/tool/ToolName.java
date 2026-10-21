package com.finalysis.chat.tool;

import java.util.Arrays;
import java.util.Optional;

/**
 * The names of the chat tools (docs/plans/phase-6.md §3.2): a closed set, so the model can't name a writer (§9). The
 * {@link ToolRegistry} has exactly one tool per name.
 */
public enum ToolName {
    GET_MONTH_TOTALS,
    GET_CATEGORY_TOTALS,
    GET_ACCOUNT_TOTALS,
    GET_TOP_MERCHANTS,
    GET_TRENDS,
    COMPARE_PERIODS,
    LIST_TRANSACTIONS,
    GET_RECURRING_CHARGES,
    GET_ALERTS,
    GET_DATA_COVERAGE,
    SEARCH_TRANSACTIONS,
    GET_MERCHANT_TOTALS;

    /** The name the model sees: {@code get_month_totals}. */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The tool a model's name means, if it is one; never matched loosely. */
    public static Optional<ToolName> fromWire(String name) {
        return Arrays.stream(values()).filter(t -> t.wire().equals(name)).findFirst();
    }
}
