package com.finalysis.analytics;

import com.finalysis.api.ApiMoney;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /api/analytics/merchants}: the merchants with the most {@code spent} over the range (ANA-01). Spending
 * rows only (expense and refund buckets, uncategorized money out), so {@code totalSpent} is −(the months'
 * {@code spending}), and the list plus {@code othersSpent} adds up to it.
 *
 * @param range       the months asked for, or the default; null when nothing was asked for and there are no
 *                    statements
 * @param limit       how many merchants were asked for
 * @param items       most {@code spent} first; ties by merchant, the null merchant last
 * @param othersSpent the {@code spent} of every merchant not listed
 * @param totalSpent  −(net of every spending row in the range): positive = net money out after refunds
 */
public record MerchantTotalsResponse(MonthRange range, Long accountId, Long categoryId, int limit,
                                     List<MerchantTotal> items, @ApiMoney BigDecimal othersSpent, int othersCount,
                                     @ApiMoney BigDecimal totalSpent, int totalCount) {

    /**
     * One merchant over the range.
     *
     * @param merchant the cleaned merchant, exactly as stored; null groups the rows that have none
     * @param spent    −(net of its spending rows): positive = money out; negative only when refunds exceed purchases
     * @param count    its spending rows
     * @param category the category of most of its rows (ties: the latest row's); null when that is no category
     */
    public record MerchantTotal(String merchant, @ApiMoney BigDecimal spent, int count, LocalDate firstDate,
                                LocalDate lastDate, CategoryRef category) {
    }
}
