package com.finalysis.categorize.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.categorize.eval.EvalMetrics.Band;
import com.finalysis.categorize.eval.EvalMetrics.Counts;
import com.finalysis.categorize.eval.EvalMetrics.RowSource;
import com.finalysis.categorize.eval.EvalMetrics.ScoredRow;
import com.finalysis.categorize.eval.EvalMetrics.SweepPoint;
import com.finalysis.categorize.eval.EvalMetrics.VariantMetrics;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The evaluation's arithmetic (docs/plans/phase-4.md, step 9a notes) on ten hand-made rows whose answers are worked
 * out by hand below, as if the run used min-confidence 0.70.
 */
class EvalMetricsTest {

    private static final List<ScoredRow> ROWS = List.of(
            rule(1, "Dining", "Dining"),                                           // correct
            rule(2, "Income", "Income"),                                           // correct
            rule(3, "Dining", "Shopping"),                                         // wrong
            answered(4, "Groceries", "Groceries", "0.920", true),                  // correct
            answered(5, "Shopping", "Dining", "0.810", true),                      // wrong
            answered(6, "Health", "Health", "0.650", false),                       // review; right below 0.70
            answered(7, "Housing", "Utilities", "0.500", false),                   // review; wrong below 0.70
            new ScoredRow(8, "d8", "Dining", null, RowSource.NONE, "INVALID_ANSWER", "INVALID", null, null),
            new ScoredRow(9, "d9", "Dining", "Dining", RowSource.DIRECT, null, "DIRECT", "Dining", null),
            new ScoredRow(10, "d10", "Person-to-Person Out", null, RowSource.NONE, null, "PERSON_TO_PERSON", null,
                    null));

    private static ScoredRow rule(int number, String expected, String predicted) {
        return new ScoredRow(number, "d" + number, expected, predicted, RowSource.RULE, null, null, null, null);
    }

    private static ScoredRow answered(int number, String expected, String answer, String confidence,
                                      boolean applied) {
        return new ScoredRow(number, "d" + number, expected, applied ? answer : null,
                applied ? RowSource.AI : RowSource.NONE, applied ? null : "LOW_CONFIDENCE",
                applied ? "APPLIED" : "LOW_CONFIDENCE", answer, new BigDecimal(confidence));
    }

    @Test
    void theHeadlineNumbers() {
        VariantMetrics metrics = EvalMetrics.of(ROWS);

        // Categorized: 1, 2, 3, 4, 5, 9. Correct: 1, 2, 4, 9. Review: 6, 7, 8. Neither: 10.
        assertThat(metrics.rows()).isEqualTo(10);
        assertThat(metrics.categorized()).isEqualTo(6);
        assertThat(metrics.correct()).isEqualTo(4);
        assertThat(metrics.wrong()).isEqualTo(2);
        assertThat(metrics.review()).isEqualTo(3);
        assertThat(metrics.uncategorized()).isEqualTo(1);
        assertThat(metrics.coveragePct()).isEqualByComparingTo("60.0");
        assertThat(metrics.accuracyPct()).isEqualByComparingTo("66.7");   // 4 / 6
        assertThat(metrics.overallPct()).isEqualByComparingTo("40.0");
        assertThat(metrics.bySource()).isEqualTo(Map.of(RowSource.RULE, new Counts(3, 2, 1),
                RowSource.AI, new Counts(2, 1, 1), RowSource.DIRECT, new Counts(1, 1, 0)));
    }

    @Test
    void confidenceBandsCountEveryAnsweredRowAppliedOrNot() {
        VariantMetrics metrics = EvalMetrics.of(ROWS);

        assertThat(metrics.answeredRows()).isEqualTo(4);   // 4, 5, 6, 7; the direct row 9 has no confidence
        List<Band> bands = metrics.confidenceBands();
        assertThat(bands).hasSize(10).extracting(Band::range).startsWith("0.0-0.1").endsWith("0.9-1.0");
        assertThat(bands.get(9)).isEqualTo(new Band("0.9-1.0", 1, 1, new BigDecimal("100.0")));
        assertThat(bands.get(8)).isEqualTo(new Band("0.8-0.9", 1, 0, new BigDecimal("0.0")));
        assertThat(bands.get(6)).isEqualTo(new Band("0.6-0.7", 1, 1, new BigDecimal("100.0")));
        assertThat(bands.get(5)).isEqualTo(new Band("0.5-0.6", 1, 0, new BigDecimal("0.0")));
        assertThat(bands.get(7)).isEqualTo(new Band("0.7-0.8", 0, 0, null));
    }

    @Test
    void aBandIncludesItsLowerBoundAndOneFallsInTheLast() {
        List<Band> bands = EvalMetrics.of(List.of(answered(1, "Dining", "Dining", "0.700", true),
                answered(2, "Dining", "Dining", "1.000", true), answered(3, "Dining", "Dining", "0.000", false),
                answered(4, "Dining", "Dining", "0.699", false))).confidenceBands();

        assertThat(bands).extracting(Band::rows).containsExactly(1, 0, 0, 0, 0, 0, 1, 1, 0, 1);
    }

    @Test
    void theSweepMovesOnlyAnsweredRows() {
        List<SweepPoint> sweep = EvalMetrics.sweep(ROWS);

        assertThat(sweep).extracting(point -> point.minConfidence().toPlainString()).containsExactly(
                "0.50", "0.55", "0.60", "0.65", "0.70", "0.75", "0.80", "0.85", "0.90", "0.95");
        // 0.50: 4, 5, 6, 7 applied. 0.55-0.65: 7 (0.50) in review. 0.70-0.80: 6 too (the run itself).
        // 0.85-0.90: 5 (0.81) too. 0.95: 4 (0.92) too. Rules, direct, invalid and unsent rows never move.
        assertThat(sweep).extracting(SweepPoint::categorized).containsExactly(8, 7, 7, 7, 6, 6, 6, 5, 5, 4);
        assertThat(sweep).extracting(SweepPoint::correct).containsExactly(5, 5, 5, 5, 4, 4, 4, 4, 4, 3);
        assertThat(sweep).extracting(SweepPoint::wrong).containsExactly(3, 2, 2, 2, 2, 2, 2, 1, 1, 1);
        assertThat(sweep).extracting(SweepPoint::review).containsExactly(1, 2, 2, 2, 3, 3, 3, 4, 4, 5);
        assertThat(sweep.getFirst().coveragePct()).isEqualByComparingTo("80.0");
        assertThat(sweep.getFirst().accuracyPct()).isEqualByComparingTo("62.5");
        assertThat(sweep.get(1).accuracyPct()).isEqualByComparingTo("71.4");   // 5 / 7
        assertThat(sweep.get(7).accuracyPct()).isEqualByComparingTo("80.0");
        assertThat(sweep.getLast().accuracyPct()).isEqualByComparingTo("75.0");
        assertThat(sweep.getLast().overallPct()).isEqualByComparingTo("30.0");
    }

    /** At the run's own threshold the sweep gives the headline numbers back: one computation, two views. */
    @Test
    void theSweepAtTheRunsThresholdIsTheHeadline() {
        VariantMetrics metrics = EvalMetrics.of(ROWS);
        SweepPoint point = EvalMetrics.at(ROWS, new BigDecimal("0.70"));

        assertThat(point).isEqualTo(new SweepPoint(new BigDecimal("0.70"), metrics.categorized(), metrics.correct(),
                metrics.wrong(), metrics.review(), metrics.coveragePct(), metrics.accuracyPct(), metrics.overallPct()));
    }

    @Test
    void percentagesRoundToOneDecimalAndNeedAWhole() {
        assertThat(EvalMetrics.pct(2, 3)).isEqualByComparingTo("66.7");
        assertThat(EvalMetrics.pct(1, 3)).isEqualByComparingTo("33.3");
        assertThat(EvalMetrics.pct(0, 0)).isNull();
        assertThat(EvalMetrics.of(List.of()).accuracyPct()).isNull();
    }
}
