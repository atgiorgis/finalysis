package com.finalysis.analytics;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

/**
 * Calendar months {@code from} to {@code to}, both included ({@code yyyy-MM} in JSON). The services check its length
 * against {@code finalysis.analytics.max-months} ({@link MonthRanges}).
 */
public record MonthRange(YearMonth from, YearMonth to) {

    public MonthRange {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("a month range needs from <= to, was " + from + " to " + to);
        }
    }

    /** How many months, both ends included. */
    public int length() {
        return (int) ChronoUnit.MONTHS.between(from, to) + 1;
    }

    /** Every month, oldest first. */
    public List<YearMonth> months() {
        return Stream.iterate(from, month -> !month.isAfter(to), month -> month.plusMonths(1)).toList();
    }

    /** The first day of {@code from}: the inclusive lower bound of a {@code txn_date} query. */
    LocalDate firstDay() {
        return from.atDay(1);
    }

    /** The first day after {@code to}: the exclusive upper bound, so the {@code txn_date} index can be used (§1.5). */
    LocalDate dayAfter() {
        return to.plusMonths(1).atDay(1);
    }
}
