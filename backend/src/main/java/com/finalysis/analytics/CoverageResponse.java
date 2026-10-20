package com.finalysis.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * {@code GET /api/analytics/coverage}: which days each account's statements cover (docs/plans/phase-5.md §1.6).
 * Every account is expected from the first day of its earliest statement to {@code lastCoveredDay}.
 *
 * @param lastCoveredDay      the last day any statement of any account covers; null without statements
 * @param latestMonth         the month of {@code lastCoveredDay}
 * @param latestCompleteMonth the latest household month that is {@code COMPLETE}; null if none
 * @param accounts            every account, by id
 */
public record CoverageResponse(LocalDate lastCoveredDay, YearMonth latestMonth, YearMonth latestCompleteMonth,
                               List<AccountCoverage> accounts) {

    /** A run of days, both ends included. */
    public record DateSpan(LocalDate start, LocalDate end) {
    }

    /**
     * One account. Each list holds at most {@value CoverageService#MAX_ITEMS} items, the latest ones; {@code truncated}
     * says one was cut.
     *
     * @param firstDay          the first day of its earliest statement; null without statements
     * @param covered           the days its statements cover, adjacent statements merged, oldest first
     * @param gaps              days between {@code firstDay} and the household's {@code lastCoveredDay} that no
     *                          statement covers, oldest first
     * @param flaggedStatements statements whose balance check failed or couldn't run, oldest first
     */
    public record AccountCoverage(long accountId, String name, LocalDate firstDay, List<DateSpan> covered,
                                  List<DateSpan> gaps, List<FlaggedStatement> flaggedStatements, boolean truncated) {
    }

    /**
     * @param flag       {@code RECONCILIATION_FAILED} or {@code BALANCE_UNVERIFIED}
     * @param reviewedAt when the user accepted it in the review queue, or null
     */
    public record FlaggedStatement(long statementId, LocalDate periodStart, LocalDate periodEnd, MonthFlagType flag,
                                   Instant reviewedAt) {
    }
}
