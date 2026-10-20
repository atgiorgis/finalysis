package com.finalysis.analytics;

import com.finalysis.analytics.BucketTotalsQuery.Cell;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One month's {@link MonthTotals} from its bucket cells (docs/plans/phase-5.md §1.1, §1.7). Plain Java: it adds
 * exact sums and divides with {@code HALF_EVEN}. The cells decide the scope, household or one account.
 */
final class MonthTotalsCalculator {

    private MonthTotalsCalculator() {
    }

    static MonthTotals totals(YearMonth month, Coverage coverage, Collection<Cell> cells, List<MonthFlag> flags) {
        Map<TxnBucket, MoneyFlow> buckets = new EnumMap<>(TxnBucket.class);
        int reviewPending = 0;
        for (Cell cell : cells) {
            buckets.merge(cell.bucket(), cell.flow(), MoneyFlow::plus);
            if (cell.bucket() == TxnBucket.UNCATEGORIZED) {
                reviewPending += cell.reviewPending();
            }
        }
        MoneyFlow income = flow(buckets, TxnBucket.INCOME);
        MoneyFlow expense = flow(buckets, TxnBucket.EXPENSE);
        MoneyFlow refunds = flow(buckets, TxnBucket.REFUND);
        MoneyFlow uncategorized = flow(buckets, TxnBucket.UNCATEGORIZED);
        MoneyFlow unlinked = flow(buckets, TxnBucket.UNLINKED_TRANSFER);
        MoneyFlow linked = flow(buckets, TxnBucket.LINKED_TRANSFER);

        BigDecimal totalIncome = income.net().add(uncategorized.moneyIn());
        BigDecimal totalSpending = expense.net().add(refunds.net()).add(uncategorized.moneyOut());
        BigDecimal net = totalIncome.add(totalSpending);
        BigDecimal savingsRate = totalIncome.signum() > 0 ? Arithmetic.percent(net, totalIncome) : null;
        BigDecimal netChange = buckets.values().stream().map(MoneyFlow::net).reduce(Arithmetic.ZERO, BigDecimal::add);
        int txnCount = buckets.values().stream().mapToInt(MoneyFlow::count).sum();

        int counted = income.count() + expense.count() + refunds.count() + uncategorized.count();
        // Magnitudes of money out only: a refund (money in) can't make the uncategorized share look smaller.
        BigDecimal spendingOut = expense.moneyOut().add(refunds.moneyOut()).add(uncategorized.moneyOut()).negate();
        DataQuality quality = new DataQuality(counted, uncategorized.count(), reviewPending, unlinked.count(),
                Arithmetic.percent(uncategorized.count(), counted),
                Arithmetic.percent(reviewPending, counted),
                Arithmetic.percent(uncategorized.moneyOut().negate(), spendingOut),
                List.copyOf(flags));

        return new MonthTotals(month, coverage, totalIncome, totalSpending, net, savingsRate, refunds, uncategorized,
                unlinked, linked, netChange, txnCount, quality);
    }

    private static MoneyFlow flow(Map<TxnBucket, MoneyFlow> buckets, TxnBucket bucket) {
        return buckets.getOrDefault(bucket, MoneyFlow.ZERO);
    }
}
