package com.finalysis.analytics;

import com.finalysis.analytics.AccountTotalsResponse.AccountTotals;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly totals per account (ANA-01; docs/plans/phase-5.md §5.2): each account's own coverage, flags, data quality
 * and {@code netChange}, its balance movement. Read-only, in one REPEATABLE READ transaction.
 */
@Service
public class AccountTotalsService {

    private final CoverageQuery coverage;
    private final MonthRanges ranges;
    private final MonthTotalsReader totals;
    private final ReferenceQuery references;

    public AccountTotalsService(CoverageQuery coverage, MonthRanges ranges, MonthTotalsReader totals,
                                ReferenceQuery references) {
        this.coverage = coverage;
        this.ranges = ranges;
        this.totals = totals;
        this.references = references;
    }

    /**
     * @param from first month; default {@code to} − (default-months − 1)
     * @param to   last month; default the month of the last covered day
     * @throws com.finalysis.api.InvalidRequestException for a range that is reversed or too long
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AccountTotalsResponse accounts(Optional<YearMonth> from, Optional<YearMonth> to) {
        CoverageMap map = coverage.load();
        Optional<MonthRange> range = ranges.resolve(from, to, map.latestMonth());
        Map<Long, String> accounts = references.accounts();
        Map<Long, List<MonthTotals>> months = range.filter(r -> !map.isEmpty())
                .map(r -> totals.byAccount(r, accounts.keySet(), map))
                .orElse(Map.of());
        List<AccountTotals> items = accounts.entrySet().stream()
                .map(account -> new AccountTotals(account.getKey(), account.getValue(),
                        months.getOrDefault(account.getKey(), List.of())))
                .toList();
        return new AccountTotalsResponse(range.orElse(null), map.latestMonth().orElse(null),
                map.latestCompleteMonth().orElse(null), items);
    }
}
