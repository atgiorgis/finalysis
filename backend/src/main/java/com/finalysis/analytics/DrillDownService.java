package com.finalysis.analytics;

import com.finalysis.analytics.DrillDownResponse.Item;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The txns behind any total (for the Phase 7 drill-down, docs/plans/phase-5.md §5.2): same range rules, same bucket
 * rule, bounded pages. Read-only, in one REPEATABLE READ transaction, so the page and its total agree.
 */
@Service
public class DrillDownService {

    private final CoverageQuery coverage;
    private final MonthRanges ranges;
    private final DrillDownQuery query;
    private final ReferenceQuery references;

    public DrillDownService(CoverageQuery coverage, MonthRanges ranges, DrillDownQuery query,
                            ReferenceQuery references) {
        this.coverage = coverage;
        this.ranges = ranges;
        this.query = query;
        this.references = references;
    }

    /**
     * @throws com.finalysis.api.InvalidRequestException for a reversed or too long range
     * @throws com.finalysis.api.NotFoundException       for an unknown account or category
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DrillDownResponse transactions(DrillDownFilter filter) {
        CoverageMap map = coverage.load();
        Optional<MonthRange> range = filter.month().map(m -> new MonthRange(m, m))
                .or(() -> ranges.resolve(filter.from(), filter.to(), map.latestMonth()));
        filter.accountId().ifPresent(references::requireAccount);
        filter.categoryId().ifPresent(references::requireCategory);
        if (range.isEmpty() || map.isEmpty()) {
            return new DrillDownResponse(range.orElse(null), MoneyFlow.ZERO, filter.page(), filter.size(), 0,
                    List.of());
        }
        MoneyFlow total = query.total(range.get(), filter);
        Map<Long, CategoryRef> categories = references.categories();
        List<Item> items = query.page(range.get(), filter).stream()
                .map(row -> new Item(row.id(), row.accountId(), row.txnDate(), row.amount(), row.merchant(),
                        row.categoryId() == null ? null : categories.get(row.categoryId()), row.bucket()))
                .toList();
        int totalPages = (total.count() + filter.size() - 1) / filter.size();
        return new DrillDownResponse(range.get(), total, filter.page(), filter.size(), totalPages, items);
    }
}
