package com.finalysis.analytics;

import com.finalysis.analytics.BucketTotalsQuery.Cell;
import com.finalysis.analytics.CoverageQuery.Flagged;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Month totals for the household or per account, from one bucket query and one statement query, so every month of
 * a response comes from the same rows. Runs inside the caller's read-only transaction.
 */
@Component
class MonthTotalsReader {

    private final BucketTotalsQuery buckets;
    private final CoverageQuery statements;

    MonthTotalsReader(BucketTotalsQuery buckets, CoverageQuery statements) {
        this.buckets = buckets;
        this.statements = statements;
    }

    /** Every month of the range for the household, or for one account. */
    List<MonthTotals> months(MonthRange range, Optional<Long> accountId, CoverageMap coverage) {
        Map<YearMonth, List<Cell>> cells = buckets.find(range, accountId, Optional.empty()).stream()
                .collect(Collectors.groupingBy(Cell::month));
        List<Flagged> flagged = statements.flagged(Optional.of(range), accountId);
        return range.months().stream()
                .map(month -> MonthTotalsCalculator.totals(month, coverage(coverage, month, accountId),
                        cells.getOrDefault(month, List.of()), flags(month, accountId, flagged, coverage)))
                .toList();
    }

    /** Every month of the range for each account, in the order given. */
    Map<Long, List<MonthTotals>> byAccount(MonthRange range, Collection<Long> accountIds, CoverageMap coverage) {
        Map<Long, Map<YearMonth, List<Cell>>> cells = buckets.find(range, Optional.empty(), Optional.empty()).stream()
                .collect(Collectors.groupingBy(Cell::accountId, Collectors.groupingBy(Cell::month)));
        List<Flagged> flagged = statements.flagged(Optional.of(range), Optional.empty());
        Map<Long, List<MonthTotals>> totals = new LinkedHashMap<>();
        for (long accountId : accountIds) {
            Map<YearMonth, List<Cell>> accountCells = cells.getOrDefault(accountId, Map.of());
            totals.put(accountId, range.months().stream()
                    .map(month -> MonthTotalsCalculator.totals(month, coverage.status(month, accountId),
                            accountCells.getOrDefault(month, List.of()),
                            flags(month, Optional.of(accountId), flagged, coverage)))
                    .toList());
        }
        return totals;
    }

    static Coverage coverage(CoverageMap coverage, YearMonth month, Optional<Long> accountId) {
        return accountId.map(id -> coverage.status(month, id)).orElseGet(() -> coverage.status(month));
    }

    /**
     * The month's flags (docs/plans/phase-5.md §1.6): the flagged statements overlapping it, oldest first, then
     * {@code PARTIAL_COVERAGE} for each account missing days of it, by id.
     */
    static List<MonthFlag> flags(YearMonth month, Optional<Long> accountId, List<Flagged> flagged,
                                 CoverageMap coverage) {
        List<MonthFlag> flags = new ArrayList<>();
        for (Flagged statement : flagged) {
            boolean inScope = accountId.isEmpty() || accountId.get() == statement.accountId();
            boolean overlaps = !statement.statement().periodStart().isAfter(month.atEndOfMonth())
                    && !statement.statement().periodEnd().isBefore(month.atDay(1));
            if (inScope && overlaps) {
                flags.add(new MonthFlag(statement.statement().flag(), statement.accountId(),
                        statement.statement().statementId(), statement.statement().reviewedAt()));
            }
        }
        List<Long> partial = accountId
                .map(id -> coverage.status(month, id) == Coverage.PARTIAL ? List.of(id) : List.<Long>of())
                .orElseGet(() -> coverage.partialAccounts(month));
        partial.forEach(id -> flags.add(new MonthFlag(MonthFlagType.PARTIAL_COVERAGE, id, null, null)));
        return flags;
    }
}
