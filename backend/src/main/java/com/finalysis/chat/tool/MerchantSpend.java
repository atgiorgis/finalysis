package com.finalysis.chat.tool;

import com.finalysis.analytics.CategoryRef;
import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.MoneyFlow;
import java.math.BigDecimal;
import java.util.List;

/**
 * What one merchant text spent over a range (docs/plans/phase-6.md §4.3): one drill-down per stored merchant it stands
 * for ({@code measure=SPENT}, the bucket rule of every total), and their sum. {@code spent} is −(Σ net), so positive is
 * money out after refunds, as {@code get_top_merchants}; a citation's drill-downs add up to it by construction.
 *
 * @param range    the months the drill-downs covered (null when there is no data)
 * @param parts    each stored merchant and its drill-down total
 * @param category the category of most of the group's spending rows, ties to the newest row's (the rule of
 *                 {@code get_top_merchants}); null when that is no category, or there are no rows
 */
public record MerchantSpend(String text, MonthRange range, List<Part> parts, BigDecimal spent, int count,
                            CategoryRef category) {

    public MerchantSpend {
        parts = List.copyOf(parts);
    }

    /** One stored merchant's spending rows. */
    public record Part(String merchant, MoneyFlow total) {
    }
}
