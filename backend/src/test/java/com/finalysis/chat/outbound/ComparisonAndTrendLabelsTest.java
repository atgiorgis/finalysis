package com.finalysis.chat.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.analytics.Average;
import com.finalysis.analytics.ComparisonMeasure;
import com.finalysis.analytics.ComparisonResponse;
import com.finalysis.analytics.MonthRange;
import com.finalysis.chat.tool.view.TrendsView;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Step 8d: the meanings the model got wrong in dev run 2, now given in the results and worked out in Java. d-c08 named
 * February as higher when March was ({@code higherPeriod}); d-t06 called the month-over-month change the change from
 * the 3-month average ({@code differenceFromAverage}).
 */
class ComparisonAndTrendLabelsTest {

    private static MonthRange range(String from, String to) {
        return new MonthRange(YearMonth.parse(from), YearMonth.parse(to));
    }

    private static ComparisonResponse compare(MonthRange a, String aValue, MonthRange b, String bValue) {
        BigDecimal difference = new BigDecimal(aValue).subtract(new BigDecimal(bValue));
        return new ComparisonResponse(ComparisonMeasure.SPENDING, null, null,
                new ComparisonResponse.Side(a, new BigDecimal(aValue), List.of()),
                new ComparisonResponse.Side(b, new BigDecimal(bValue), List.of()), difference, null);
    }

    @ParameterizedTest(name = "{0} to {1} → {2}")
    @CsvSource({
            "2026-03, 2026-03, 2026-03",
            "2026-01, 2026-03, 2026-Q1", "2026-04, 2026-06, 2026-Q2", "2026-07, 2026-09, 2026-Q3",
            "2026-10, 2026-12, 2026-Q4",
            "2026-02, 2026-04, 2026-02 to 2026-04", "2026-01, 2026-04, 2026-01 to 2026-04",
            "2025-11, 2026-01, 2025-11 to 2026-01", "2026-01, 2026-12, 2026-01 to 2026-12"})
    void aPeriodIsLabelledAsItsOwnMonthQuarterOrRange(String from, String to, String label) {
        assertThat(OutboundProjector.periodLabel(range(from, to))).isEqualTo(label);
    }

    /** d-c08: February $1,687.98, March $1,809.48; March is higher whichever side it is on. */
    @Test
    void theHigherPeriodIsNamedByItsLabelWhicheverSideItIsOn() {
        MonthRange february = range("2026-02", "2026-02");
        MonthRange march = range("2026-03", "2026-03");

        assertThat(OutboundProjector.higherPeriod(compare(february, "1687.98", march, "1809.48"))).isEqualTo("2026-03");
        assertThat(OutboundProjector.higherPeriod(compare(march, "1809.48", february, "1687.98"))).isEqualTo("2026-03");
        assertThat(OutboundProjector.higherPeriod(compare(march, "100.00", february, "100.00"))).isEqualTo("SAME");
        assertThat(OutboundProjector.higherPeriod(compare(range("2026-07", "2026-09"), "1.00",
                range("2026-04", "2026-06"), "2.00"))).isEqualTo("2026-Q2");
    }

    /** d-t06: April $225.00 against a 3-month average of $66.67 is $158.33 above it (the $175.00 is month over month). */
    @Test
    void theDifferenceFromEachAverageIsTheMonthLessTheAverage() {
        TrendsView.TrailingAverage above = OutboundProjector.trailing(new BigDecimal("225.00"),
                new Average(new BigDecimal("66.67"), 3, 3));
        assertThat(above).isEqualTo(new TrendsView.TrailingAverage("66.67", 3, 3, "158.33"));

        TrendsView.TrailingAverage below = OutboundProjector.trailing(new BigDecimal("40.00"),
                new Average(new BigDecimal("66.67"), 2, 3));
        assertThat(below.differenceFromAverage()).isEqualTo("-26.67");

        assertThat(OutboundProjector.trailing(new BigDecimal("40.00"), new Average(null, 0, 3)))
                .isEqualTo(new TrendsView.TrailingAverage(null, 0, 3, null));
        assertThat(OutboundProjector.trailing(new BigDecimal("40.00"), null)).isNull();
    }
}
