package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.analytics.CoverageResponse.DateSpan;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link CoverageMap} on its own (docs/plans/phase-5.md §1.6), with the plan's sample-data example and edge cases. */
class CoverageMapTest {

    private static final long APEX = 1;
    private static final long CHECKING = 2;
    private static final long SAVINGS = 3;
    private static final long SUMMIT = 4;

    /**
     * §1.6's worked example: Apex Visa from 2026-03-15 to 2026-10-14, the other accounts April to September. March is
     * partial (Apex only, from the 15th), April to September complete, October partial; 2026-09 is the latest
     * complete month.
     */
    @Test
    void thePlansSampleDataExample() {
        CoverageMap map = new CoverageMap(Map.of(
                APEX, List.of(span("2026-03-15", "2026-10-14")),
                CHECKING, List.of(span("2026-04-01", "2026-09-30")),
                SAVINGS, List.of(span("2026-04-01", "2026-09-30")),
                SUMMIT, List.of(span("2026-04-01", "2026-09-30"))));

        assertThat(map.status(YearMonth.of(2026, 2))).isEqualTo(Coverage.NONE);
        assertThat(map.status(YearMonth.of(2026, 3))).isEqualTo(Coverage.PARTIAL);
        assertThat(map.partialAccounts(YearMonth.of(2026, 3))).as("the others aren't expected yet")
                .containsExactly(APEX);
        for (int month = 4; month <= 9; month++) {
            assertThat(map.status(YearMonth.of(2026, month))).as("2026-%02d", month).isEqualTo(Coverage.COMPLETE);
        }
        assertThat(map.status(YearMonth.of(2026, 10))).isEqualTo(Coverage.PARTIAL);
        assertThat(map.partialAccounts(YearMonth.of(2026, 10))).containsExactly(APEX, CHECKING, SAVINGS, SUMMIT);
        assertThat(map.status(YearMonth.of(2026, 11))).isEqualTo(Coverage.NONE);

        assertThat(map.lastCoveredDay()).contains(LocalDate.parse("2026-10-14"));
        assertThat(map.latestMonth()).contains(YearMonth.of(2026, 10));
        assertThat(map.latestCompleteMonth()).contains(YearMonth.of(2026, 9));
        assertThat(map.gaps(CHECKING)).containsExactly(span("2026-10-01", "2026-10-14"));
        assertThat(map.gaps(APEX)).isEmpty();
    }

    @Test
    void oneAccountsStatusIsItsOwnDays() {
        CoverageMap map = new CoverageMap(Map.of(APEX, List.of(span("2026-03-15", "2026-10-14"))));

        assertThat(map.status(YearMonth.of(2026, 3), APEX)).isEqualTo(Coverage.PARTIAL);
        assertThat(map.status(YearMonth.of(2026, 4), APEX)).isEqualTo(Coverage.COMPLETE);
        assertThat(map.status(YearMonth.of(2026, 11), APEX)).isEqualTo(Coverage.NONE);
        assertThat(map.status(YearMonth.of(2026, 4), CHECKING)).as("an account without statements")
                .isEqualTo(Coverage.NONE);
    }

    /** A missing statement in one account makes the household month partial; in every account, it is NONE. */
    @Test
    void gapsInsideTheData() {
        CoverageMap map = new CoverageMap(Map.of(
                CHECKING, List.of(span("2026-01-01", "2026-01-31"), span("2026-03-01", "2026-04-30")),
                SAVINGS, List.of(span("2026-01-01", "2026-04-30"))));

        assertThat(map.status(YearMonth.of(2026, 2))).isEqualTo(Coverage.PARTIAL);
        assertThat(map.partialAccounts(YearMonth.of(2026, 2))).containsExactly(CHECKING);
        assertThat(map.status(YearMonth.of(2026, 2), CHECKING)).isEqualTo(Coverage.NONE);
        assertThat(map.gaps(CHECKING)).containsExactly(span("2026-02-01", "2026-02-28"));

        CoverageMap bothMissing = new CoverageMap(Map.of(
                CHECKING, List.of(span("2026-01-01", "2026-01-31"), span("2026-03-01", "2026-03-31")),
                SAVINGS, List.of(span("2026-01-01", "2026-01-31"), span("2026-03-01", "2026-03-31"))));
        assertThat(bothMissing.status(YearMonth.of(2026, 2))).isEqualTo(Coverage.NONE);
        assertThat(bothMissing.latestCompleteMonth()).contains(YearMonth.of(2026, 3));
    }

    /** One covered day short of the month is partial; February's length is the calendar's. */
    @Test
    void completeMeansEveryDayOfTheMonth() {
        CoverageMap leap = new CoverageMap(Map.of(CHECKING, List.of(span("2028-02-01", "2028-02-28"))));
        CoverageMap full = new CoverageMap(Map.of(CHECKING, List.of(span("2028-02-01", "2028-02-29"))));

        assertThat(leap.status(YearMonth.of(2028, 2))).isEqualTo(Coverage.PARTIAL);
        assertThat(full.status(YearMonth.of(2028, 2))).isEqualTo(Coverage.COMPLETE);
    }

    @Test
    void withoutStatementsThereIsNoLatestMonth() {
        CoverageMap empty = new CoverageMap(Map.of(CHECKING, List.of()));

        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.latestMonth()).isEqualTo(Optional.empty());
        assertThat(empty.latestCompleteMonth()).isEqualTo(Optional.empty());
        assertThat(empty.status(YearMonth.of(2026, 1))).isEqualTo(Coverage.NONE);
        assertThat(empty.firstDay(CHECKING)).isNull();
        assertThat(empty.gaps(CHECKING)).isEmpty();
    }

    private static DateSpan span(String start, String end) {
        return new DateSpan(LocalDate.parse(start), LocalDate.parse(end));
    }
}
