package com.finalysis.analytics;

import com.finalysis.analytics.BucketTotalsQuery.Cell;
import com.finalysis.analytics.CategoryTotalsResponse.MonthCoverage;
import com.finalysis.analytics.CategoryTotalsResponse.Row;
import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money per bucket and category, per month (ANA-01; docs/plans/phase-5.md §1, §5.2). One row per bucket and
 * category, except linked transfers (one row: the link wins over any category) and uncategorized txns (one row, with
 * its review-pending count). Read-only, in one REPEATABLE READ transaction.
 */
@Service
public class CategoryTotalsService {

    /** Income first, then spending, then the lines that are in no total. */
    private static final List<TxnBucket> ROW_ORDER = List.of(TxnBucket.INCOME, TxnBucket.EXPENSE, TxnBucket.REFUND,
            TxnBucket.UNCATEGORIZED, TxnBucket.UNLINKED_TRANSFER, TxnBucket.LINKED_TRANSFER);

    private static final Comparator<Row> ORDER = Comparator
            .comparingInt((Row row) -> ROW_ORDER.indexOf(row.bucket()))
            .thenComparing(row -> row.category() == null ? "" : row.category().name())
            .thenComparingLong(row -> row.category() == null ? 0 : row.category().id());

    private final CoverageQuery coverage;
    private final MonthRanges ranges;
    private final BucketTotalsQuery buckets;
    private final ReferenceQuery references;

    public CategoryTotalsService(CoverageQuery coverage, MonthRanges ranges, BucketTotalsQuery buckets,
                                 ReferenceQuery references) {
        this.coverage = coverage;
        this.ranges = ranges;
        this.buckets = buckets;
        this.references = references;
    }

    /**
     * @param from          first month; default {@code to} − (default-months − 1)
     * @param to            last month; default the month of the last covered day
     * @param accountId     only this account's txns; empty = every account
     * @param categoryId    only txns with this category, in whatever bucket they land; empty = every category
     * @param uncategorized true: only the Uncategorized row; not with {@code categoryId}
     * @throws InvalidRequestException for a reversed or too long range, or {@code categoryId} with
     *                                 {@code uncategorized}
     * @throws com.finalysis.api.NotFoundException for an unknown account or category
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CategoryTotalsResponse categories(Optional<YearMonth> from, Optional<YearMonth> to,
                                             Optional<Long> accountId, Optional<Long> categoryId,
                                             boolean uncategorized) {
        if (uncategorized && categoryId.isPresent()) {
            throw new InvalidRequestException(List.of(new InvalidField("uncategorized",
                    "can't be combined with categoryId")));
        }
        CoverageMap map = coverage.load();
        Optional<MonthRange> range = ranges.resolve(from, to, map.latestMonth());
        accountId.ifPresent(references::requireAccount);
        categoryId.ifPresent(references::requireCategory);
        if (range.isEmpty() || map.isEmpty()) {
            return new CategoryTotalsResponse(range.orElse(null), accountId.orElse(null), categoryId.orElse(null),
                    uncategorized, List.of(), List.of(), range.isEmpty() ? null : Arithmetic.ZERO);
        }

        List<YearMonth> months = range.get().months();
        Map<Long, CategoryRef> categories = references.categories();
        Map<RowKey, Map<YearMonth, List<Cell>>> cellsByRow = new LinkedHashMap<>();
        List<Cell> found = buckets.find(range.get(), accountId, categoryId);
        BigDecimal spending = spending(categoryId.isEmpty() ? found
                : buckets.find(range.get(), accountId, Optional.empty()));
        for (Cell cell : found) {
            if (uncategorized && cell.bucket() != TxnBucket.UNCATEGORIZED) {
                continue;
            }
            Long rowCategory = cell.bucket() == TxnBucket.LINKED_TRANSFER ? null : cell.categoryId();
            cellsByRow.computeIfAbsent(new RowKey(cell.bucket(), rowCategory), key -> new LinkedHashMap<>())
                    .computeIfAbsent(cell.month(), month -> new ArrayList<>()).add(cell);
        }

        List<Row> rows = new ArrayList<>();
        cellsByRow.forEach((key, byMonth) -> {
            List<CategoryTotalsResponse.Cell> cells = months.stream()
                    .map(month -> cell(key.bucket(), month, byMonth.getOrDefault(month, List.of())))
                    .toList();
            MoneyFlow total = cells.stream().map(CategoryTotalsResponse.Cell::flow)
                    .reduce(MoneyFlow.ZERO, MoneyFlow::plus);
            rows.add(new Row(key.bucket(), key.categoryId() == null ? null : categories.get(key.categoryId()), total,
                    cells, share(key.bucket(), total, spending)));
        });
        rows.sort(ORDER);

        List<MonthCoverage> monthCoverage = months.stream()
                .map(month -> new MonthCoverage(month, MonthTotalsReader.coverage(map, month, accountId)))
                .toList();
        return new CategoryTotalsResponse(range.get(), accountId.orElse(null), categoryId.orElse(null), uncategorized,
                monthCoverage, List.copyOf(rows), spending);
    }

    /**
     * Total spending of the cells, by {@link MonthTotalsCalculator}'s rule: expenses and refunds net, plus uncategorized
     * money out. The cells are every category's, so a row's share has the household's (or the account's) spending as
     * its base.
     */
    private static BigDecimal spending(List<Cell> cells) {
        BigDecimal total = Arithmetic.ZERO;
        for (Cell cell : cells) {
            total = total.add(switch (cell.bucket()) {
                case EXPENSE, REFUND -> cell.flow().net();
                case UNCATEGORIZED -> cell.flow().moneyOut();
                default -> BigDecimal.ZERO;
            });
        }
        return total;
    }

    /** A spending row's part of the total spending, in Java (step 8b: shares are never the model's arithmetic). */
    private static BigDecimal share(TxnBucket bucket, MoneyFlow total, BigDecimal spending) {
        BigDecimal part = switch (bucket) {
            case EXPENSE, REFUND -> total.net();
            case UNCATEGORIZED -> total.moneyOut();
            default -> null;
        };
        return part == null ? null : Arithmetic.percent(part, spending);
    }

    private static CategoryTotalsResponse.Cell cell(TxnBucket bucket, YearMonth month, List<Cell> cells) {
        MoneyFlow flow = cells.stream().map(Cell::flow).reduce(MoneyFlow.ZERO, MoneyFlow::plus);
        Integer reviewPending = bucket == TxnBucket.UNCATEGORIZED
                ? cells.stream().mapToInt(Cell::reviewPending).sum() : null;
        return new CategoryTotalsResponse.Cell(month, flow, reviewPending);
    }

    private record RowKey(TxnBucket bucket, Long categoryId) {
    }
}
