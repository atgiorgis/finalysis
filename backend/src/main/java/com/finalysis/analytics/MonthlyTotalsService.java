package com.finalysis.analytics;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly totals with refunds, transfers and data quality (ANA-01, ANA-02, ANA-08; docs/plans/phase-5.md §1, §5).
 * Read-only, so Phase 6 can offer it to the chatbot as a tool as it is (§8).
 *
 * <p>One REPEATABLE READ transaction: the buckets, coverage and flags of a response come from the same snapshot.
 */
@Service
public class MonthlyTotalsService {

    private final CoverageQuery coverage;
    private final MonthRanges ranges;
    private final MonthTotalsReader totals;
    private final ReferenceQuery references;

    public MonthlyTotalsService(CoverageQuery coverage, MonthRanges ranges, MonthTotalsReader totals,
                                ReferenceQuery references) {
        this.coverage = coverage;
        this.ranges = ranges;
        this.totals = totals;
        this.references = references;
    }

    /**
     * @param from      first month; default {@code to} − (default-months − 1)
     * @param to        last month; default the month of the last covered day
     * @param accountId one account's totals, coverage and flags; empty = the household
     * @throws com.finalysis.api.InvalidRequestException for a range that is reversed or too long
     * @throws com.finalysis.api.NotFoundException       for an unknown account
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MonthlyTotalsResponse months(Optional<YearMonth> from, Optional<YearMonth> to, Optional<Long> accountId) {
        CoverageMap map = coverage.load();
        Optional<MonthRange> range = ranges.resolve(from, to, map.latestMonth());
        accountId.ifPresent(references::requireAccount);
        List<MonthTotals> months = range.filter(r -> !map.isEmpty())
                .map(r -> totals.months(r, accountId, map))
                .orElse(List.of());
        return new MonthlyTotalsResponse(range.orElse(null), accountId.orElse(null), map.latestMonth().orElse(null),
                map.latestCompleteMonth().orElse(null), months);
    }
}
