package com.finalysis.analytics;

import com.finalysis.analytics.BucketTotalsQuery.Cell;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Month over month, year over year and trailing averages per category and in total (ANA-03; docs/plans/phase-5.md
 * §7). One bucket query over M − 12 … M; Java turns its cells into magnitudes and does every division. Read-only, in
 * one REPEATABLE READ transaction.
 */
@Service
public class TrendsService {

    private static final int YEAR = 12;

    /** Spending lines first, then income lines, then refunds; categories by name inside each. */
    private static final Comparator<RowKey> ORDER = Comparator
            .comparingInt(RowKey::rank)
            .thenComparing(key -> key.category() == null ? "" : key.category().name())
            .thenComparingLong(key -> key.category() == null ? 0 : key.category().id());

    private final CoverageQuery coverage;
    private final BucketTotalsQuery buckets;
    private final ReferenceQuery references;

    public TrendsService(CoverageQuery coverage, BucketTotalsQuery buckets, ReferenceQuery references) {
        this.coverage = coverage;
        this.buckets = buckets;
        this.references = references;
    }

    /**
     * @param month      M; default the latest {@code COMPLETE} month, or the latest month if none is complete
     * @param accountId  one account's txns, and its own coverage decides which months count; empty = the household
     * @param categoryId only that category's rows (no totals, no Uncategorized line); empty = every row
     * @throws com.finalysis.api.NotFoundException for an unknown account or category
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TrendsResponse trends(Optional<YearMonth> month, Optional<Long> accountId, Optional<Long> categoryId) {
        CoverageMap map = coverage.load();
        accountId.ifPresent(references::requireAccount);
        categoryId.ifPresent(references::requireCategory);
        Optional<YearMonth> m = month.or(map::latestCompleteMonth).or(map::latestMonth);
        if (m.isEmpty() || map.isEmpty()) {
            return new TrendsResponse(m.orElse(null), accountId.orElse(null), categoryId.orElse(null), List.of());
        }

        YearMonth target = m.get();
        List<Cell> cells = buckets.find(new MonthRange(target.minusMonths(YEAR), target), accountId, categoryId);
        return response(target, cells, accountId, categoryId, references.categories(), map);
    }

    /**
     * The household trends of every month of {@code range}, oldest first, from one bucket query over
     * {@code from − 12 … to}: each month's response is exactly what {@link #trends(Optional, Optional, Optional)}
     * returns for it. For the category spike check, which judges every month of an alert range (§6.3); without
     * statements, each month has no rows.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<TrendsResponse> trends(MonthRange range) {
        CoverageMap map = coverage.load();
        List<TrendsResponse> responses = new ArrayList<>();
        if (map.isEmpty()) {
            range.months().forEach(month -> responses.add(new TrendsResponse(month, null, null, List.of())));
            return responses;
        }
        Map<Long, CategoryRef> categories = references.categories();
        List<Cell> cells = buckets.find(new MonthRange(range.from().minusMonths(YEAR), range.to()), Optional.empty(),
                Optional.empty());
        for (YearMonth target : range.months()) {
            YearMonth first = target.minusMonths(YEAR);
            List<Cell> window = cells.stream()
                    .filter(c -> !c.month().isBefore(first) && !c.month().isAfter(target))
                    .toList();
            responses.add(response(target, window, Optional.empty(), Optional.empty(), categories, map));
        }
        return responses;
    }

    /** The rows of month {@code target} from the cells of {@code target − 12 … target}. */
    private static TrendsResponse response(YearMonth target, List<Cell> cells, Optional<Long> accountId,
                                           Optional<Long> categoryId, Map<Long, CategoryRef> categories,
                                           CoverageMap map) {
        Map<RowKey, Map<YearMonth, BigDecimal>> amounts = new LinkedHashMap<>();
        if (categoryId.isEmpty()) {
            amounts.put(RowKey.TOTAL_SPENDING, new HashMap<>());
            amounts.put(RowKey.TOTAL_INCOME, new HashMap<>());
        }
        for (Cell cell : cells) {
            CategoryRef category = cell.categoryId() == null ? null : categories.get(cell.categoryId());
            switch (cell.bucket()) {
                case EXPENSE -> add(amounts, RowKey.category(TxnBucket.EXPENSE, category), cell, cell.net().negate());
                case INCOME -> add(amounts, RowKey.category(TxnBucket.INCOME, category), cell, cell.net());
                case REFUND -> add(amounts, RowKey.category(TxnBucket.REFUND, category), cell, cell.net());
                case UNCATEGORIZED -> {
                    if (categoryId.isEmpty() && cell.moneyOut().signum() != 0) {
                        add(amounts, RowKey.UNCATEGORIZED, cell, cell.moneyOut().negate());
                    }
                }
                case LINKED_TRANSFER, UNLINKED_TRANSFER -> {
                    // in no total
                }
            }
            if (categoryId.isEmpty()) {
                switch (cell.bucket()) {
                    case EXPENSE, REFUND -> add(amounts, RowKey.TOTAL_SPENDING, cell, cell.net().negate());
                    case UNCATEGORIZED -> {
                        add(amounts, RowKey.TOTAL_SPENDING, cell, cell.moneyOut().negate());
                        add(amounts, RowKey.TOTAL_INCOME, cell, cell.moneyIn());
                    }
                    case INCOME -> add(amounts, RowKey.TOTAL_INCOME, cell, cell.net());
                    case LINKED_TRANSFER, UNLINKED_TRANSFER -> {
                        // in no total
                    }
                }
            }
        }

        List<RowKey> keys = new ArrayList<>(amounts.keySet());
        keys.sort(ORDER);
        List<TrendRow> rows = keys.stream()
                .map(key -> row(key, amounts.get(key), target, accountId, map))
                .toList();
        return new TrendsResponse(target, accountId.orElse(null), categoryId.orElse(null), rows);
    }

    private static TrendRow row(RowKey key, Map<YearMonth, BigDecimal> byMonth, YearMonth m,
                                Optional<Long> accountId, CoverageMap map) {
        BigDecimal amount = value(byMonth, m);
        YearMonth previous = m.minusMonths(1);
        BigDecimal previousMonth = coverage(map, previous, accountId) == Coverage.NONE ? null : value(byMonth, previous);
        BigDecimal changeAmount = previousMonth == null ? null : amount.subtract(previousMonth);
        BigDecimal changePercent = previousMonth == null ? null : Arithmetic.percent(changeAmount, previousMonth.abs());
        YearMonth lastYear = m.minusMonths(YEAR);
        BigDecimal sameMonthLastYear = coverage(map, lastYear, accountId) == Coverage.NONE
                ? null : value(byMonth, lastYear);
        return new TrendRow(key.line(), key.bucket(), key.category(), key.measure(), amount, previousMonth,
                changeAmount, changePercent, trailing(byMonth, m, 3, accountId, map),
                trailing(byMonth, m, 6, accountId, map), sameMonthLastYear, coverage(map, m, accountId));
    }

    /** The mean over the complete months among M − 1 … M − n; M itself is never in it (§7). */
    static Average trailing(Map<YearMonth, BigDecimal> byMonth, YearMonth m, int n, Optional<Long> accountId,
                            CoverageMap map) {
        BigDecimal sum = Arithmetic.ZERO;
        int used = 0;
        for (int back = 1; back <= n; back++) {
            YearMonth month = m.minusMonths(back);
            if (coverage(map, month, accountId) == Coverage.COMPLETE) {
                sum = sum.add(value(byMonth, month));
                used++;
            }
        }
        return new Average(used == 0 ? null : Arithmetic.average(sum, used), used, n);
    }

    private static Coverage coverage(CoverageMap map, YearMonth month, Optional<Long> accountId) {
        return MonthTotalsReader.coverage(map, month, accountId);
    }

    private static BigDecimal value(Map<YearMonth, BigDecimal> byMonth, YearMonth month) {
        return byMonth.getOrDefault(month, Arithmetic.ZERO);
    }

    private static void add(Map<RowKey, Map<YearMonth, BigDecimal>> amounts, RowKey key, Cell cell,
                            BigDecimal amount) {
        amounts.computeIfAbsent(key, k -> new HashMap<>()).merge(cell.month(), amount, BigDecimal::add);
    }

    /** A trend line: its kind, and for a category line its bucket and category. */
    private record RowKey(TrendLine line, TxnBucket bucket, CategoryRef category) {

        static final RowKey TOTAL_SPENDING = new RowKey(TrendLine.TOTAL_SPENDING, null, null);
        static final RowKey TOTAL_INCOME = new RowKey(TrendLine.TOTAL_INCOME, null, null);
        static final RowKey UNCATEGORIZED = new RowKey(TrendLine.UNCATEGORIZED, null, null);

        static RowKey category(TxnBucket bucket, CategoryRef category) {
            return new RowKey(TrendLine.CATEGORY, bucket, category);
        }

        TrendMeasure measure() {
            return switch (line) {
                case TOTAL_SPENDING, UNCATEGORIZED -> TrendMeasure.SPENT;
                case TOTAL_INCOME -> TrendMeasure.RECEIVED;
                case CATEGORY -> bucket == TxnBucket.EXPENSE ? TrendMeasure.SPENT : TrendMeasure.RECEIVED;
            };
        }

        int rank() {
            return switch (line) {
                case TOTAL_SPENDING -> 0;
                case CATEGORY -> switch (bucket) {
                    case EXPENSE -> 1;
                    case INCOME -> 4;
                    default -> 5;
                };
                case UNCATEGORIZED -> 2;
                case TOTAL_INCOME -> 3;
            };
        }
    }
}
