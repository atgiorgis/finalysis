package com.finalysis.categorize.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * How near each item came to an eligible example in one variant with embeddings (C or D; docs/plans/phase-4.md,
 * step 9c notes). Pure Java: the similarities come from the harness's exact scan, every count is made here.
 *
 * <p>An <b>eligible example</b> is what the app could have used: a merchant embedded by the same model, not the item's
 * own, with a USER or RULE category in the item's direction. The scan is exact, over every such merchant; the app
 * itself asks the HNSW index for the 40 nearest, so it can only see fewer. "At or above a floor" uses {@code >=} on
 * the exact similarity, as the app does with {@code min-similarity}; the histogram uses the nearest similarity as
 * reported (4 places), so an item shown at 0.8000 is in the 0.80-0.85 band.
 *
 * @param floor          the configured {@code min-similarity}, which {@code examplesAboveFloor} counts against
 * @param items          the items the variant sent to the model or assigned directly
 * @param withoutExample items with no eligible example at all (or no vector of their own)
 * @param histogram      nearest-example similarity in {@value #BAND_COUNT} bands of 0.05; below 0 counts in the first,
 *                       1.0 in the last
 * @param floorCounts    items whose nearest example is at or above each of {@link #FLOORS}: those that would get at
 *                       least one example at that floor
 * @param perItem        each item, in the order decided
 */
record ExampleDiagnostics(BigDecimal floor, int items, int withoutExample, List<SimilarityBand> histogram,
                          List<FloorCount> floorCounts, List<ItemNeighbours> perItem) {

    static final int BAND_COUNT = 20;
    static final BigDecimal BAND_WIDTH = new BigDecimal("0.05");
    static final List<BigDecimal> FLOORS = List.of(new BigDecimal("0.50"), new BigDecimal("0.55"),
            new BigDecimal("0.60"), new BigDecimal("0.65"));

    /**
     * One item and its eligible examples' similarities, as scanned.
     *
     * @param outcome the item's outcome in the run (APPLIED, LOW_CONFIDENCE, INVALID or DIRECT)
     */
    record ItemSimilarities(String merchant, int direction, String outcome, List<Double> similarities) {

        ItemSimilarities {
            similarities = List.copyOf(similarities);
        }
    }

    /**
     * @param nearestSimilarity  the highest similarity to any eligible example, rounded to 4 places; null without one
     * @param eligibleExamples   eligible examples, at any similarity
     * @param examplesAboveFloor eligible examples at or above the configured floor
     */
    record ItemNeighbours(String merchant, int direction, String outcome, BigDecimal nearestSimilarity,
                          int eligibleExamples, int examplesAboveFloor) {
    }

    record SimilarityBand(String range, int items) {
    }

    record FloorCount(BigDecimal floor, int items) {
    }

    static ExampleDiagnostics of(List<ItemSimilarities> items, BigDecimal floor) {
        int[] bands = new int[BAND_COUNT];
        int[] atFloor = new int[FLOORS.size()];
        int withoutExample = 0;
        List<ItemNeighbours> perItem = new ArrayList<>();
        for (ItemSimilarities item : items) {
            BigDecimal nearest = item.similarities().stream().max(Double::compare).map(BigDecimal::valueOf)
                    .orElse(null);
            BigDecimal shown = nearest == null ? null : nearest.setScale(4, RoundingMode.HALF_UP);
            int aboveFloor = (int) item.similarities().stream()
                    .filter(similarity -> BigDecimal.valueOf(similarity).compareTo(floor) >= 0).count();
            perItem.add(new ItemNeighbours(item.merchant(), item.direction(), item.outcome(), shown,
                    item.similarities().size(), aboveFloor));
            if (nearest == null) {
                withoutExample++;
                continue;
            }
            bands[band(shown)]++;
            for (int i = 0; i < FLOORS.size(); i++) {
                if (nearest.compareTo(FLOORS.get(i)) >= 0) {
                    atFloor[i]++;
                }
            }
        }
        List<SimilarityBand> histogram = new ArrayList<>();
        for (int i = 0; i < BAND_COUNT; i++) {
            histogram.add(new SimilarityBand(bound(i) + "-" + bound(i + 1), bands[i]));
        }
        List<FloorCount> floorCounts = new ArrayList<>();
        for (int i = 0; i < FLOORS.size(); i++) {
            floorCounts.add(new FloorCount(FLOORS.get(i), atFloor[i]));
        }
        return new ExampleDiagnostics(floor, items.size(), withoutExample, List.copyOf(histogram),
                List.copyOf(floorCounts), List.copyOf(perItem));
    }

    /** The band of a similarity: {@code floor(similarity / 0.05)}, kept within the first and last band. */
    static int band(BigDecimal similarity) {
        int band = similarity.divide(BAND_WIDTH, 0, RoundingMode.FLOOR).intValue();
        return Math.clamp(band, 0, BAND_COUNT - 1);
    }

    private static String bound(int band) {
        return BAND_WIDTH.multiply(BigDecimal.valueOf(band)).toPlainString();
    }
}
