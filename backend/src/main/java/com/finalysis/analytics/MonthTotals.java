package com.finalysis.analytics;

import com.finalysis.api.ApiMoney;
import com.finalysis.api.ApiPercent;
import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * One month's totals, for the household or one account (ANA-01, ANA-02; docs/plans/phase-5.md §1.1). Money is signed
 * as stored (GR-03), in the statements' currency, with two decimals: negative = money out.
 *
 * <p>Invariant: {@code income + spending + unlinkedTransfers.net + linkedTransfers.net = netChange}, the sum of every
 * txn's amount in the month. {@code refunds} and {@code uncategorized} are already inside {@code income} and
 * {@code spending}.
 *
 * @param coverage           how much of the month the statements cover; a figure for a month that isn't
 *                           {@code COMPLETE} can still grow
 * @param income             INCOME bucket plus uncategorized money in (≥ 0 in a normal month)
 * @param spending           EXPENSE and REFUND buckets plus uncategorized money out (≤ 0 in a normal month); refunds
 *                           in an expense category reduce it
 * @param net                {@code income + spending}: cash flow without transfers
 * @param savingsRatePercent {@code net / income × 100}, one decimal; null when {@code income ≤ 0}
 * @param refunds            Refunds &amp; Rebates on its own line; already in {@code spending}, never in {@code income}
 * @param uncategorized      txns without a category; money out is in {@code spending}, money in in {@code income}
 * @param unlinkedTransfers  Transfer-category txns with no link: in neither total
 * @param linkedTransfers    txns on a transfer link: in neither total (GR-04)
 * @param netChange          the sum of every amount, transfers included: for one account, its balance movement
 * @param txnCount           every txn in the month, in any bucket
 */
public record MonthTotals(YearMonth month, Coverage coverage,
                          @ApiMoney BigDecimal income, @ApiMoney BigDecimal spending, @ApiMoney BigDecimal net,
                          @ApiPercent BigDecimal savingsRatePercent,
                          MoneyFlow refunds, MoneyFlow uncategorized, MoneyFlow unlinkedTransfers,
                          MoneyFlow linkedTransfers, @ApiMoney BigDecimal netChange, int txnCount,
                          DataQuality dataQuality) {
}
