package com.finalysis.analytics;

import com.finalysis.analytics.MerchantTotalsQuery.Cell;
import com.finalysis.analytics.MerchantTotalsResponse.MerchantTotal;
import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Top merchants by {@code spent} (ANA-01; docs/plans/phase-5.md §5.2). Read-only, in one REPEATABLE READ transaction.
 */
@Service
public class MerchantTotalsService {

    public static final int DEFAULT_LIMIT = 10;

    private static final Comparator<MerchantTotal> MOST_SPENT = Comparator
            .comparing(MerchantTotal::spent, Comparator.reverseOrder())
            .thenComparing(MerchantTotal::merchant, Comparator.nullsLast(Comparator.naturalOrder()));

    private final CoverageQuery coverage;
    private final MonthRanges ranges;
    private final MerchantTotalsQuery merchants;
    private final ReferenceQuery references;
    private final AnalyticsProperties properties;

    public MerchantTotalsService(CoverageQuery coverage, MonthRanges ranges, MerchantTotalsQuery merchants,
                                 ReferenceQuery references, AnalyticsProperties properties) {
        this.coverage = coverage;
        this.ranges = ranges;
        this.merchants = merchants;
        this.references = references;
        this.properties = properties;
    }

    /**
     * @param from       first month; default {@code to} − (default-months − 1)
     * @param to         last month; default the month of the last covered day
     * @param accountId  only this account's txns; empty = every account
     * @param categoryId only txns with this category; empty = every category
     * @param limit      1 to {@code finalysis.analytics.merchants.max-limit}
     * @throws InvalidRequestException for a reversed or too long range, or a limit out of bounds
     * @throws com.finalysis.api.NotFoundException for an unknown account or category
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MerchantTotalsResponse merchants(Optional<YearMonth> from, Optional<YearMonth> to,
                                            Optional<Long> accountId, Optional<Long> categoryId, int limit) {
        int maxLimit = properties.merchants().maxLimit();
        if (limit < 1 || limit > maxLimit) {
            throw new InvalidRequestException(List.of(new InvalidField("limit", "must be 1 to " + maxLimit)));
        }
        CoverageMap map = coverage.load();
        Optional<MonthRange> range = ranges.resolve(from, to, map.latestMonth());
        accountId.ifPresent(references::requireAccount);
        categoryId.ifPresent(references::requireCategory);
        if (range.isEmpty() || map.isEmpty()) {
            return new MerchantTotalsResponse(range.orElse(null), accountId.orElse(null), categoryId.orElse(null),
                    limit, List.of(), Arithmetic.ZERO, 0, Arithmetic.ZERO, 0);
        }

        Map<Long, CategoryRef> categories = references.categories();
        Map<String, List<Cell>> byMerchant = new HashMap<>();
        List<Cell> withoutMerchant = new ArrayList<>();
        for (Cell cell : merchants.find(range.get(), accountId, categoryId)) {
            if (cell.merchant() == null) {
                withoutMerchant.add(cell);
            } else {
                byMerchant.computeIfAbsent(cell.merchant(), m -> new ArrayList<>()).add(cell);
            }
        }
        List<MerchantTotal> all = new ArrayList<>();
        byMerchant.forEach((merchant, cells) -> all.add(total(merchant, cells, categories)));
        if (!withoutMerchant.isEmpty()) {
            all.add(total(null, withoutMerchant, categories));
        }
        all.sort(MOST_SPENT);

        List<MerchantTotal> items = List.copyOf(all.subList(0, Math.min(limit, all.size())));
        List<MerchantTotal> others = all.subList(items.size(), all.size());
        return new MerchantTotalsResponse(range.get(), accountId.orElse(null), categoryId.orElse(null), limit, items,
                sumSpent(others), others.stream().mapToInt(MerchantTotal::count).sum(),
                sumSpent(all), all.stream().mapToInt(MerchantTotal::count).sum());
    }

    private static MerchantTotal total(String merchant, List<Cell> cells, Map<Long, CategoryRef> categories) {
        BigDecimal net = cells.stream().map(Cell::net).reduce(Arithmetic.ZERO, BigDecimal::add);
        int count = cells.stream().mapToInt(Cell::count).sum();
        LocalDate first = cells.stream().map(Cell::firstDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate last = cells.stream().map(Cell::lastDate).max(LocalDate::compareTo).orElseThrow();
        return new MerchantTotal(merchant, net.negate(), count, first, last, mainCategory(cells, categories));
    }

    /**
     * The category of a group of stored merchants' spending rows over a range, by the one rule every merchant list
     * uses (docs/plans/phase-6.md, step 6 review decision 28): the category of most rows, ties to the newest row's.
     * Chat's search and merchant totals use it, so a merchant has the same category in every tool.
     *
     * @return empty when the rows have no category, or there are no rows
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<CategoryRef> category(MonthRange range, Optional<Long> accountId, List<String> merchants) {
        if (merchants.isEmpty()) {
            return Optional.empty();
        }
        List<Cell> cells = this.merchants.find(range, accountId, Optional.empty(), merchants);
        return cells.isEmpty() ? Optional.empty()
                : Optional.ofNullable(mainCategory(cells, references.categories()));
    }

    /**
     * Most rows; ties to the category of the latest row, then the lower id (no category before any). Cells of several
     * merchants are merged per category first.
     */
    private static CategoryRef mainCategory(List<Cell> cells, Map<Long, CategoryRef> categories) {
        Map<Long, Cell> byCategory = new HashMap<>();
        Cell none = null;
        for (Cell cell : cells) {
            if (cell.categoryId() == null) {
                none = none == null ? cell : merge(none, cell);
            } else {
                byCategory.merge(cell.categoryId(), cell, MerchantTotalsService::merge);
            }
        }
        List<Cell> merged = new ArrayList<>(byCategory.values());
        if (none != null) {
            merged.add(none);
        }
        Cell main = merged.stream().max(Comparator.comparingInt(Cell::count).thenComparing(Cell::lastDate)
                .thenComparing(Cell::categoryId, Comparator.nullsLast(Comparator.<Long>reverseOrder())))
                .orElseThrow();
        return main.categoryId() == null ? null : categories.get(main.categoryId());
    }

    private static Cell merge(Cell a, Cell b) {
        return new Cell(null, a.categoryId(), a.net().add(b.net()), a.count() + b.count(),
                a.firstDate().isBefore(b.firstDate()) ? a.firstDate() : b.firstDate(),
                a.lastDate().isAfter(b.lastDate()) ? a.lastDate() : b.lastDate());
    }

    private static BigDecimal sumSpent(List<MerchantTotal> totals) {
        return totals.stream().map(MerchantTotal::spent).filter(Objects::nonNull)
                .reduce(Arithmetic.ZERO, BigDecimal::add);
    }
}
