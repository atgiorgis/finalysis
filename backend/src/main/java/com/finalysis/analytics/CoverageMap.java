package com.finalysis.analytics;

import com.finalysis.analytics.CoverageResponse.DateSpan;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Which days each account's statements cover, and what that makes of each month (docs/plans/phase-5.md §1.6). Plain
 * Java over the merged statement periods {@link CoverageQuery} reads.
 *
 * <p>An account is <em>expected</em> on every day from the first day of its earliest statement to the last day any
 * account covers (accounts don't close in Phase 5). A month is:
 * <ul>
 *   <li>{@code NONE} when no account covers any day of it;</li>
 *   <li>{@code COMPLETE} when every account expected in it covers every day of the month;</li>
 *   <li>{@code PARTIAL} otherwise.</li>
 * </ul>
 * "Every day of the month", not only the expected ones: a month an account starts or ends in the middle of is
 * {@code PARTIAL}, as the plan's examples have it (a card statement starting on the 15th; the month of the last
 * covered day).
 */
final class CoverageMap {

    private final Map<Long, List<DateSpan>> covered;
    private final LocalDate lastCoveredDay;

    /** @param covered each account's covered spans, merged (none adjacent or overlapping), oldest first */
    CoverageMap(Map<Long, List<DateSpan>> covered) {
        this.covered = new TreeMap<>(covered);
        this.covered.replaceAll((account, spans) -> List.copyOf(spans));
        this.covered.values().removeIf(List::isEmpty);
        this.lastCoveredDay = this.covered.values().stream().map(spans -> spans.getLast().end())
                .max(LocalDate::compareTo).orElse(null);
    }

    boolean isEmpty() {
        return covered.isEmpty();
    }

    Optional<LocalDate> lastCoveredDay() {
        return Optional.ofNullable(lastCoveredDay);
    }

    /** The month of the last covered day: the default end of a range. */
    Optional<YearMonth> latestMonth() {
        return lastCoveredDay().map(YearMonth::from);
    }

    /** The latest household month that is {@code COMPLETE}, looking back no further than the earliest statement. */
    Optional<YearMonth> latestCompleteMonth() {
        if (isEmpty()) {
            return Optional.empty();
        }
        YearMonth earliest = covered.values().stream().map(spans -> YearMonth.from(spans.getFirst().start()))
                .min(YearMonth::compareTo).orElseThrow();
        for (YearMonth month = latestMonth().orElseThrow(); !month.isBefore(earliest); month = month.minusMonths(1)) {
            if (status(month) == Coverage.COMPLETE) {
                return Optional.of(month);
            }
        }
        return Optional.empty();
    }

    /** The household's coverage of the month. */
    Coverage status(YearMonth month) {
        boolean anyCovered = covered.keySet().stream().anyMatch(account -> coveredDays(account, month) > 0);
        if (!anyCovered) {
            return Coverage.NONE;
        }
        return partialAccounts(month).isEmpty() ? Coverage.COMPLETE : Coverage.PARTIAL;
    }

    /** One account's coverage of the month: {@code NONE}, every day ({@code COMPLETE}) or some. */
    Coverage status(YearMonth month, long accountId) {
        long days = coveredDays(accountId, month);
        if (days == 0) {
            return Coverage.NONE;
        }
        return days == month.lengthOfMonth() ? Coverage.COMPLETE : Coverage.PARTIAL;
    }

    /** Accounts expected in the month that miss some day of it, by id: the household's {@code PARTIAL_COVERAGE}. */
    List<Long> partialAccounts(YearMonth month) {
        List<Long> partial = new ArrayList<>();
        for (Long account : covered.keySet()) {
            if (expectedIn(account, month) && coveredDays(account, month) < month.lengthOfMonth()) {
                partial.add(account);
            }
        }
        return partial;
    }

    /** The first day of the account's earliest statement, or null without statements. */
    LocalDate firstDay(long accountId) {
        List<DateSpan> spans = covered.get(accountId);
        return spans == null ? null : spans.getFirst().start();
    }

    List<DateSpan> covered(long accountId) {
        return covered.getOrDefault(accountId, List.of());
    }

    /** Days from the account's first day to the household's last covered day that none of its statements covers. */
    List<DateSpan> gaps(long accountId) {
        List<DateSpan> spans = covered(accountId);
        if (spans.isEmpty()) {
            return List.of();
        }
        List<DateSpan> gaps = new ArrayList<>();
        for (int i = 1; i < spans.size(); i++) {
            gaps.add(new DateSpan(spans.get(i - 1).end().plusDays(1), spans.get(i).start().minusDays(1)));
        }
        LocalDate end = spans.getLast().end();
        if (end.isBefore(lastCoveredDay)) {
            gaps.add(new DateSpan(end.plusDays(1), lastCoveredDay));
        }
        return Collections.unmodifiableList(gaps);
    }

    private boolean expectedIn(long accountId, YearMonth month) {
        LocalDate first = firstDay(accountId);
        return first != null && !first.isAfter(month.atEndOfMonth()) && !lastCoveredDay.isBefore(month.atDay(1));
    }

    private long coveredDays(long accountId, YearMonth month) {
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        long days = 0;
        for (DateSpan span : covered(accountId)) {
            LocalDate start = span.start().isAfter(monthStart) ? span.start() : monthStart;
            LocalDate end = span.end().isBefore(monthEnd) ? span.end() : monthEnd;
            if (!start.isAfter(end)) {
                days += ChronoUnit.DAYS.between(start, end) + 1;
            }
        }
        return days;
    }
}
