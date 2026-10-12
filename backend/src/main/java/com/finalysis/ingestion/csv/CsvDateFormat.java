package com.finalysis.ingestion.csv;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Date patterns in CSV mappings, such as {@code MM/dd/yyyy}. Parsing is strict: 02/30/2026 is an
 * error, not March 2. Month names are read in English ({@link Locale#US}).
 */
public final class CsvDateFormat {

    public static final int MAX_LENGTH = 64;

    // Day above 12 and single-digit month and day, so swapped or missing fields can't round-trip.
    private static final List<LocalDate> PROBES = List.of(LocalDate.of(2031, 11, 27), LocalDate.of(2004, 2, 9));

    private CsvDateFormat() {
    }

    /**
     * The strict formatter for a pattern, or empty when the pattern is invalid or doesn't contain a
     * year ({@code y} or {@code u}), a month ({@code M} or {@code L}) and a day of month ({@code d}),
     * or when it contains anything a plain date can't fill (hours, zones).
     */
    public static Optional<DateTimeFormatter> formatter(String pattern) {
        if (pattern == null || pattern.isEmpty() || pattern.length() > MAX_LENGTH || !hasYearMonthDay(pattern)) {
            return Optional.empty();
        }
        DateTimeFormatter formatter;
        try {
            formatter = new DateTimeFormatterBuilder()
                    .appendPattern(pattern)
                    // yyyy is year-of-era; STRICT needs the era to resolve it.
                    .parseDefaulting(ChronoField.ERA, 1)
                    .toFormatter(Locale.US)
                    .withResolverStyle(ResolverStyle.STRICT);
            for (LocalDate probe : PROBES) {
                if (!LocalDate.parse(formatter.format(probe), formatter).equals(probe)) {
                    return Optional.empty();
                }
            }
        } catch (IllegalArgumentException | DateTimeException e) {
            return Optional.empty();
        }
        return Optional.of(formatter);
    }

    /** Pattern letters outside quoted literals ('at', '' for a quote). */
    private static boolean hasYearMonthDay(String pattern) {
        boolean year = false;
        boolean month = false;
        boolean day = false;
        boolean quoted = false;
        for (char c : pattern.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted) {
                year |= c == 'y' || c == 'u';
                month |= c == 'M' || c == 'L';
                day |= c == 'd';
            }
        }
        return year && month && day;
    }
}
