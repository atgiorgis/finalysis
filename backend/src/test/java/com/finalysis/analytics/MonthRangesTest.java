package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import java.time.YearMonth;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Range defaults and bounds (docs/plans/phase-5.md §5.1), and the properties that set them (§3.3). */
class MonthRangesTest {

    private static final Optional<YearMonth> NONE = Optional.empty();
    private static final Optional<YearMonth> LATEST = Optional.of(YearMonth.of(2026, 4));

    private final MonthRanges ranges = new MonthRanges(new AnalyticsProperties(24, 6, null));

    @Test
    void defaultsEndAtTheLatestMonth() {
        assertThat(ranges.resolve(NONE, NONE, LATEST)).contains(range("2025-11", "2026-04"));
        assertThat(ranges.resolve(NONE, month("2026-02"), LATEST)).contains(range("2025-09", "2026-02"));
        assertThat(ranges.resolve(month("2026-01"), NONE, LATEST)).contains(range("2026-01", "2026-04"));
        assertThat(ranges.resolve(month("2026-07"), NONE, LATEST)).as("a from after the data ends at itself")
                .contains(range("2026-07", "2026-07"));
    }

    @Test
    void withoutDataOnlyAGivenEndMakesARange() {
        assertThat(ranges.resolve(NONE, NONE, NONE)).isEmpty();
        assertThat(ranges.resolve(month("2026-01"), NONE, NONE)).contains(range("2026-01", "2026-01"));
        assertThat(ranges.resolve(NONE, month("2026-03"), NONE)).contains(range("2025-10", "2026-03"));
    }

    @Test
    void theRangeIsAtMostMaxMonthsBothEndsIncluded() {
        assertThat(ranges.resolve(month("2024-05"), month("2026-04"), LATEST)).map(MonthRange::length).contains(24);
        assertInvalid(() -> ranges.resolve(month("2024-04"), month("2026-04"), LATEST), "to");
        assertInvalid(() -> ranges.resolve(month("2024-04"), NONE, LATEST), "from");
    }

    @Test
    void fromAfterToIsInvalid() {
        assertInvalid(() -> ranges.resolve(month("2026-03"), month("2026-02"), LATEST), "to");
        assertThat(ranges.resolve(month("2026-03"), month("2026-03"), LATEST)).map(MonthRange::length).contains(1);
    }

    @Test
    void aMonthRangeListsItsMonthsAndBounds() {
        MonthRange range = range("2025-11", "2026-02");

        assertThat(range.months()).containsExactly(YearMonth.of(2025, 11), YearMonth.of(2025, 12),
                YearMonth.of(2026, 1), YearMonth.of(2026, 2));
        assertThat(range.firstDay()).hasToString("2025-11-01");
        assertThat(range.dayAfter()).as("exclusive").hasToString("2026-03-01");
        assertThatIllegalArgumentException().isThrownBy(() -> range("2026-02", "2026-01"));
    }

    @Test
    void badPropertiesFailStartup() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AnalyticsProperties(0, 1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new AnalyticsProperties(121, 6, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new AnalyticsProperties(24, 0, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new AnalyticsProperties(6, 7, null));
        assertThat(new AnalyticsProperties(120, 120, null).defaultMonths()).isEqualTo(120);
    }

    private static void assertInvalid(ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(InvalidRequestException.class, e ->
                assertThat(e.getErrors()).extracting(InvalidField::field).containsExactly(field));
    }

    private static Optional<YearMonth> month(String month) {
        return Optional.of(YearMonth.parse(month));
    }

    private static MonthRange range(String from, String to) {
        return new MonthRange(YearMonth.parse(from), YearMonth.parse(to));
    }
}
