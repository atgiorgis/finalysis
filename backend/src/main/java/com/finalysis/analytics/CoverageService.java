package com.finalysis.analytics;

import com.finalysis.analytics.CoverageQuery.Flagged;
import com.finalysis.analytics.CoverageResponse.AccountCoverage;
import com.finalysis.analytics.CoverageResponse.DateSpan;
import com.finalysis.analytics.CoverageResponse.FlaggedStatement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which days each account's statements cover, its gaps and its flagged statements (docs/plans/phase-5.md §1.6,
 * §5.2). Read-only, in one REPEATABLE READ transaction.
 */
@Service
public class CoverageService {

    /** Most items per list and account; the latest are kept, so the response can't grow with the history. */
    public static final int MAX_ITEMS = 100;

    private final CoverageQuery coverage;
    private final ReferenceQuery references;

    public CoverageService(CoverageQuery coverage, ReferenceQuery references) {
        this.coverage = coverage;
        this.references = references;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CoverageResponse coverage() {
        CoverageMap map = coverage.load();
        Map<Long, List<FlaggedStatement>> flagged = coverage.flagged(Optional.empty(), Optional.empty()).stream()
                .collect(Collectors.groupingBy(Flagged::accountId,
                        Collectors.mapping(Flagged::statement, Collectors.toList())));
        List<AccountCoverage> accounts = references.accounts().entrySet().stream()
                .map(account -> {
                    long id = account.getKey();
                    List<DateSpan> covered = map.covered(id);
                    List<DateSpan> gaps = map.gaps(id);
                    List<FlaggedStatement> statements = flagged.getOrDefault(id, List.of());
                    boolean truncated = covered.size() > MAX_ITEMS || gaps.size() > MAX_ITEMS
                            || statements.size() > MAX_ITEMS;
                    return new AccountCoverage(id, account.getValue(), map.firstDay(id), map.closedOn(id),
                            latest(covered),
                            latest(gaps), latest(statements), truncated);
                })
                .toList();
        return new CoverageResponse(map.lastCoveredDay().orElse(null), map.latestMonth().orElse(null),
                map.latestCompleteMonth().orElse(null), accounts);
    }

    private static <T> List<T> latest(List<T> items) {
        return List.copyOf(items.subList(Math.max(0, items.size() - MAX_ITEMS), items.size()));
    }
}
