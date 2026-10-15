package com.finalysis.categorize.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The accuracy numbers of one variant (CAT-05; docs/plans/phase-4.md, step 9a notes), from scored rows. Pure Java:
 * every count and percentage is computed here, never by a model.
 *
 * <p>Definitions, per row of the set (one txn each):
 * <ul>
 *   <li><b>categorized</b>: the txn has a category (a linked txn with none counts as Transfer, §1.1);</li>
 *   <li><b>correct</b>: categorized with the expected category; <b>wrong</b>: categorized with another;</li>
 *   <li><b>review</b>: left uncategorized for the review queue (low confidence or an invalid answer);</li>
 *   <li><b>uncategorized</b>: neither (no rule matched in variant A, never sent, or not reached);</li>
 *   <li>coverage = categorized / rows, accuracy = correct / categorized, overall = correct / rows.</li>
 * </ul>
 * Confidence bands and the threshold sweep use every answered row, applied or not: what the model said, with the
 * confidence it gave. Direct assignments have no confidence and are counted on their own.
 */
final class EvalMetrics {

    static final BigDecimal SWEEP_FROM = new BigDecimal("0.50");
    static final BigDecimal SWEEP_STEP = new BigDecimal("0.05");
    static final int SWEEP_POINTS = 10;   // 0.50 to 0.95
    static final int BANDS = 10;          // 0.1 wide; 1.0 falls in the last

    private EvalMetrics() {
    }

    /** Where a row's category came from. */
    enum RowSource { RULE, AI, DIRECT, USER, NONE }

    /**
     * One row after a variant ran.
     *
     * @param predicted    the category the txn has, or null
     * @param reviewReason why it waits for review, or null
     * @param outcome      the AI's outcome for its item, or null (rules, or not reached)
     * @param aiCategory   what the model answered (applied or not), or the direct example's category
     * @param confidence   the model's confidence, applied or not; null without a model answer
     */
    record ScoredRow(int number, String description, String expected, String predicted, RowSource source,
                     String reviewReason, String outcome, String aiCategory, BigDecimal confidence) {

        boolean categorized() {
            return predicted != null;
        }

        boolean correct() {
            return predicted != null && predicted.equals(expected);
        }

        boolean inReview() {
            return predicted == null && reviewReason != null;
        }

        /** A model answer with a confidence: the rows bands and the sweep are about. */
        boolean answered() {
            return aiCategory != null && confidence != null;
        }
    }

    record Counts(int categorized, int correct, int wrong) {
    }

    /** One confidence band: answered rows whose confidence is in {@code [from, to)} (the last includes 1.0). */
    record Band(String range, int rows, int correct, BigDecimal accuracyPct) {
    }

    record VariantMetrics(int rows, int categorized, int correct, int wrong, int review, int uncategorized,
                          BigDecimal coveragePct, BigDecimal accuracyPct, BigDecimal overallPct,
                          Map<RowSource, Counts> bySource, int answeredRows, List<Band> confidenceBands) {
    }

    /** The headline numbers if {@code min-confidence} had been {@code minConfidence}. */
    record SweepPoint(BigDecimal minConfidence, int categorized, int correct, int wrong, int review,
                      BigDecimal coveragePct, BigDecimal accuracyPct, BigDecimal overallPct) {
    }

    static VariantMetrics of(List<ScoredRow> rows) {
        int categorized = 0;
        int correct = 0;
        int review = 0;
        Map<RowSource, int[]> bySource = new EnumMap<>(RowSource.class);
        for (ScoredRow row : rows) {
            if (row.categorized()) {
                categorized++;
                int[] counts = bySource.computeIfAbsent(row.source(), source -> new int[2]);
                counts[0]++;
                if (row.correct()) {
                    correct++;
                    counts[1]++;
                }
            } else if (row.inReview()) {
                review++;
            }
        }
        Map<RowSource, Counts> sources = new EnumMap<>(RowSource.class);
        bySource.forEach((source, counts) ->
                sources.put(source, new Counts(counts[0], counts[1], counts[0] - counts[1])));
        List<ScoredRow> answered = rows.stream().filter(ScoredRow::answered).toList();
        return new VariantMetrics(rows.size(), categorized, correct, categorized - correct, review,
                rows.size() - categorized - review, pct(categorized, rows.size()), pct(correct, categorized),
                pct(correct, rows.size()), Map.copyOf(sources), answered.size(), bands(answered));
    }

    /** {@link #SWEEP_POINTS} thresholds from {@link #SWEEP_FROM} in steps of {@link #SWEEP_STEP}. No model call. */
    static List<SweepPoint> sweep(List<ScoredRow> rows) {
        List<SweepPoint> points = new ArrayList<>();
        for (int i = 0; i < SWEEP_POINTS; i++) {
            points.add(at(rows, SWEEP_FROM.add(SWEEP_STEP.multiply(BigDecimal.valueOf(i)))));
        }
        return points;
    }

    /**
     * The numbers at another threshold: an answered row is categorized with the model's answer when its confidence
     * is at least {@code minConfidence}, and in review otherwise; every other row is as it was.
     */
    static SweepPoint at(List<ScoredRow> rows, BigDecimal minConfidence) {
        List<ScoredRow> moved = rows.stream().map(row -> {
            if (!row.answered()) {
                return row;
            }
            boolean applied = row.confidence().compareTo(minConfidence) >= 0;
            return new ScoredRow(row.number(), row.description(), row.expected(), applied ? row.aiCategory() : null,
                    applied ? RowSource.AI : RowSource.NONE, applied ? null : "LOW_CONFIDENCE", row.outcome(),
                    row.aiCategory(), row.confidence());
        }).toList();
        VariantMetrics metrics = of(moved);
        return new SweepPoint(minConfidence, metrics.categorized(), metrics.correct(), metrics.wrong(),
                metrics.review(), metrics.coveragePct(), metrics.accuracyPct(), metrics.overallPct());
    }

    private static List<Band> bands(List<ScoredRow> answered) {
        int[] counts = new int[BANDS];
        int[] correct = new int[BANDS];
        for (ScoredRow row : answered) {
            int band = Math.min(BANDS - 1,
                    row.confidence().multiply(BigDecimal.valueOf(BANDS)).setScale(0, RoundingMode.FLOOR).intValue());
            counts[band]++;
            if (row.aiCategory().equals(row.expected())) {
                correct[band]++;
            }
        }
        List<Band> bands = new ArrayList<>();
        for (int i = 0; i < BANDS; i++) {
            bands.add(new Band(bound(i) + "-" + bound(i + 1), counts[i], correct[i], pct(correct[i], counts[i])));
        }
        return bands;
    }

    private static String bound(int tenths) {
        return BigDecimal.valueOf(tenths, 1).toPlainString();
    }

    /** {@code part / whole} as a percentage with one decimal; null when {@code whole} is 0. */
    static BigDecimal pct(int part, int whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1,
                RoundingMode.HALF_UP);
    }
}
