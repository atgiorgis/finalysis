package com.finalysis.analytics;

import com.finalysis.api.ApiMoney;
import com.finalysis.api.ApiPercent;
import java.math.BigDecimal;

/**
 * One line of the trends for a month M (ANA-03, docs/plans/phase-5.md §7). Amounts are positive magnitudes in the
 * row's {@code measure}, with two decimals.
 *
 * @param line              which line this is; {@code category} is set only for {@code CATEGORY}
 * @param bucket            for a {@code CATEGORY} line, the bucket its txns are in ({@code EXPENSE}, {@code INCOME} or
 *                          {@code REFUND}); null otherwise
 * @param amount            in M
 * @param previousMonth     in M − 1; null when M − 1 is {@code NONE} (no data to compare with)
 * @param changeAmount      {@code amount − previousMonth}; null with {@code previousMonth}
 * @param changePercent     {@code changeAmount / |previousMonth| × 100}, one decimal; null when {@code previousMonth}
 *                          is null or 0
 * @param trailing3         the mean over the complete months among M − 1 … M − 3
 * @param trailing6         the mean over the complete months among M − 1 … M − 6
 * @param sameMonthLastYear in M − 12; null when M − 12 is {@code NONE} (year over year)
 * @param monthCoverage     M's coverage, so a partial month's lower figure isn't misread
 */
public record TrendRow(TrendLine line, TxnBucket bucket, CategoryRef category, TrendMeasure measure,
                       @ApiMoney BigDecimal amount, @ApiMoney BigDecimal previousMonth,
                       @ApiMoney BigDecimal changeAmount, @ApiPercent BigDecimal changePercent,
                       Average trailing3, Average trailing6, @ApiMoney BigDecimal sameMonthLastYear,
                       Coverage monthCoverage) {
}
