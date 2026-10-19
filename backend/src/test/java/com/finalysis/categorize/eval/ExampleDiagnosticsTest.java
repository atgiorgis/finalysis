package com.finalysis.categorize.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.categorize.eval.ExampleDiagnostics.FloorCount;
import com.finalysis.categorize.eval.ExampleDiagnostics.ItemNeighbours;
import com.finalysis.categorize.eval.ExampleDiagnostics.ItemSimilarities;
import com.finalysis.categorize.eval.ExampleDiagnostics.SimilarityBand;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The examples diagnostics' math (docs/plans/phase-4.md, step 9c notes), on hand-made similarities. Every expected
 * number is worked out by hand in the comments.
 */
class ExampleDiagnosticsTest {

    private static final BigDecimal FLOOR = new BigDecimal("0.65");

    private static final List<ItemSimilarities> ITEMS = List.of(
            item("ALPHA", -1, "APPLIED", 0.71, 0.66, 0.65, 0.20),   // nearest 0.71; 3 at or above 0.65
            item("BRAVO", -1, "LOW_CONFIDENCE", 0.62, 0.10),        // nearest 0.62; none above
            item("CHARLIE", 1, "APPLIED", 0.50),                    // nearest exactly 0.50; none above
            item("DELTA", -1, "INVALID"),                           // no eligible example
            item("ECHO", -1, "DIRECT", 1.0, 0.98),                  // nearest 1.0; 2 above
            item("FOXTROT", -1, "APPLIED", -0.04, -0.20),           // nearest below 0: the first band
            item("GOLF", -1, "APPLIED", 0.549999, 0.3));            // nearest just under 0.55

    private static ItemSimilarities item(String merchant, int direction, String outcome, double... similarities) {
        return new ItemSimilarities(merchant, direction, outcome,
                Arrays.stream(similarities).boxed().toList());
    }

    @Test
    void eachItemsNearestExampleAndHowManyAreAboveTheFloor() {
        ExampleDiagnostics diagnostics = ExampleDiagnostics.of(ITEMS, FLOOR);

        assertThat(diagnostics.floor()).isEqualByComparingTo("0.65");
        assertThat(diagnostics.items()).isEqualTo(7);
        assertThat(diagnostics.withoutExample()).isOne();
        assertThat(diagnostics.perItem()).containsExactly(
                new ItemNeighbours("ALPHA", -1, "APPLIED", new BigDecimal("0.7100"), 4, 3),
                new ItemNeighbours("BRAVO", -1, "LOW_CONFIDENCE", new BigDecimal("0.6200"), 2, 0),
                new ItemNeighbours("CHARLIE", 1, "APPLIED", new BigDecimal("0.5000"), 1, 0),
                new ItemNeighbours("DELTA", -1, "INVALID", null, 0, 0),
                new ItemNeighbours("ECHO", -1, "DIRECT", new BigDecimal("1.0000"), 2, 2),
                new ItemNeighbours("FOXTROT", -1, "APPLIED", new BigDecimal("-0.0400"), 2, 0),
                new ItemNeighbours("GOLF", -1, "APPLIED", new BigDecimal("0.5500"), 2, 0));
    }

    @Test
    void theFloorIsInclusive() {
        // ALPHA's 0.65 counts at a floor of 0.65, as the app's min-similarity does (>=).
        assertThat(ExampleDiagnostics.of(List.of(item("A", -1, "APPLIED", 0.65)), FLOOR).perItem().getFirst()
                .examplesAboveFloor()).isOne();
        assertThat(ExampleDiagnostics.of(List.of(item("A", -1, "APPLIED", 0.6499)), FLOOR).perItem().getFirst()
                .examplesAboveFloor()).isZero();
    }

    @Test
    void theHistogramHasTwentyBandsOfFiveHundredths() {
        List<SimilarityBand> histogram = ExampleDiagnostics.of(ITEMS, FLOOR).histogram();

        assertThat(histogram).hasSize(20);
        assertThat(histogram.getFirst().range()).isEqualTo("0.00-0.05");
        assertThat(histogram.get(13).range()).isEqualTo("0.65-0.70");
        assertThat(histogram.getLast().range()).isEqualTo("0.95-1.00");
        // FOXTROT (-0.04) in the first; CHARLIE (0.50) in 0.50-0.55; GOLF (0.549999, shown as 0.5500) in 0.55-0.60;
        // BRAVO (0.62) in 0.60-0.65;
        // ALPHA (0.71) in 0.70-0.75; ECHO (1.0) in the last. DELTA has no nearest and is in no band.
        int[] expected = new int[20];
        expected[0] = 1;
        expected[10] = 1;
        expected[11] = 1;
        expected[12] = 1;
        expected[14] = 1;
        expected[19] = 1;
        assertThat(histogram).extracting(SimilarityBand::items)
                .containsExactly(Arrays.stream(expected).boxed().toArray(Integer[]::new));
        assertThat(histogram.stream().mapToInt(SimilarityBand::items).sum()).isEqualTo(6);
    }

    @Test
    void bandEdgesFallInTheUpperBand() {
        assertThat(ExampleDiagnostics.band(new BigDecimal("0.65"))).isEqualTo(13);
        assertThat(ExampleDiagnostics.band(new BigDecimal("0.6499"))).isEqualTo(12);
        assertThat(ExampleDiagnostics.band(new BigDecimal("0.05"))).isOne();
        assertThat(ExampleDiagnostics.band(BigDecimal.ZERO)).isZero();
        assertThat(ExampleDiagnostics.band(new BigDecimal("-0.5"))).isZero();
        assertThat(ExampleDiagnostics.band(new BigDecimal("0.95"))).isEqualTo(19);
        assertThat(ExampleDiagnostics.band(BigDecimal.ONE)).isEqualTo(19);
        // A double such as 0.65 (stored as 0.65000000000000002...) still lands on its own band.
        assertThat(ExampleDiagnostics.band(BigDecimal.valueOf(0.65))).isEqualTo(13);
    }

    @Test
    void itemsThatWouldGetExamplesAtEachFloor() {
        List<FloorCount> floors = ExampleDiagnostics.of(ITEMS, FLOOR).floorCounts();

        // Nearest: ALPHA 0.71, BRAVO 0.62, CHARLIE 0.50, ECHO 1.0, FOXTROT -0.04, GOLF 0.549999; DELTA none.
        // 0.50: ALPHA, BRAVO, CHARLIE, ECHO, GOLF = 5. 0.55: GOLF drops (0.549999), CHARLIE too = 3.
        // 0.60: ALPHA, BRAVO, ECHO = 3. 0.65: BRAVO drops = 2.
        assertThat(floors).extracting(floor -> floor.floor().toPlainString())
                .containsExactly("0.50", "0.55", "0.60", "0.65");
        assertThat(floors).extracting(FloorCount::items).containsExactly(5, 3, 3, 2);
    }

    @Test
    void noItemsGiveEmptyCounts() {
        ExampleDiagnostics diagnostics = ExampleDiagnostics.of(List.of(), FLOOR);

        assertThat(diagnostics.items()).isZero();
        assertThat(diagnostics.withoutExample()).isZero();
        assertThat(diagnostics.histogram()).hasSize(20).extracting(SimilarityBand::items).containsOnly(0);
        assertThat(diagnostics.floorCounts()).extracting(FloorCount::items).containsExactly(0, 0, 0, 0);
    }
}
