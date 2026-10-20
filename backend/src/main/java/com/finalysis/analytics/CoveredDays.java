package com.finalysis.analytics;

import java.time.LocalDate;

/**
 * An account's covered days as of some day (docs/plans/phase-5.md §1.6): the first day of its earliest statement, and
 * the last day its statements covered by then.
 */
public record CoveredDays(LocalDate firstDay, LocalDate lastDay) {
}
