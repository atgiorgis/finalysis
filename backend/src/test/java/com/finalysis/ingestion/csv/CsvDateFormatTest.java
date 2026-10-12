package com.finalysis.ingestion.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CsvDateFormatTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "MM/dd/yyyy  | 04/01/2026",
            "M/d/yyyy    | 4/1/2026",
            "yyyy-MM-dd  | 2026-04-01",
            "dd-MMM-yyyy | 01-Apr-2026",
            "MM/dd/yy    | 04/01/26",
            "dd/MM/yyyy  | 01/04/2026",
            "uuuu-MM-dd  | 2026-04-01",
            "yyyyMMdd    | 20260401",
            "d MMMM yyyy | 1 April 2026",
    })
    void acceptsDatePatterns(String pattern, String text) {
        DateTimeFormatter formatter = CsvDateFormat.formatter(pattern).orElseThrow();

        assertThat(LocalDate.parse(text, formatter)).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",                 // empty
            "yyyy-MM",          // no day
            "MM/dd",            // no year
            "yyyy-dd",          // no month
            "yyyy-mm-dd",       // mm is minutes
            "yyyy-MM-DD",       // DD is day of year
            "YYYY-MM-dd",       // week-based year
            "'yyyy'-MM-dd",     // year only inside a quoted literal
            "yyyy-MM-dd HH:mm", // a date has no time to format
            "yyyy-MM-dd {",     // reserved character
            "yyyy-MM-dd'",      // unterminated quote
    })
    void rejectsPatternsWithoutAStrictYearMonthAndDay(String pattern) {
        assertThat(CsvDateFormat.formatter(pattern)).isEmpty();
    }

    @Test
    void rejectsNullAndOverlongPatterns() {
        assertThat(CsvDateFormat.formatter(null)).isEmpty();
        assertThat(CsvDateFormat.formatter("yyyy-MM-dd" + "'x'".repeat(20))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"02/30/2026", "13/01/2026", "00/10/2026"})
    void parsingIsStrict(String text) {
        DateTimeFormatter formatter = CsvDateFormat.formatter("MM/dd/yyyy").orElseThrow();

        assertThatThrownBy(() -> LocalDate.parse(text, formatter)).isInstanceOf(DateTimeParseException.class);
    }
}
