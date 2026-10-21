package com.finalysis.chat.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * Memory never carries a figure (docs/plans/phase-6.md §7.1, §7.2): every figure the grounding extractor finds is
 * replaced, markers go, and what is left is checked again by the same extractor.
 */
class SummaryFigureStripperTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "You spent $1,183.67 on Shopping in August 2026 [1].|You spent [amount] on Shopping in August 2026.",
            "Savings rate 42.8% in May 2026 [c1, t3].|Savings rate [percent] in May 2026.",
            "You made 8 grocery trips on Aug 22.|You made [number] grocery trips on Aug 22.",
            "Net was (45.00) and 1234.5 percent|Net was [amount] and [percent]",
            "About $1.2k on CARD-1 since 2026-03-01|About [amount] on CARD-1 since 2026-03-01",
            "Your 3rd largest merchant was 7-ELEVEN.|Your 3rd largest merchant was [number]-ELEVEN.",
            "One alert, 0 duplicates.|One alert, 0 duplicates.",
            "A <b>bold</b> {claim}|A b bold /b claim"})
    void everyFigureIsReplacedAndMarkersGo(String text, String stripped) {
        assertThat(SummaryFigureStripper.strip(text)).isEqualTo(stripped);
        assertThat(SummaryFigureStripper.figureFree(SummaryFigureStripper.strip(text))).isTrue();
    }

    @Test
    void nothingLeftIsEmpty() {
        assertThat(SummaryFigureStripper.strip(null)).isEmpty();
        assertThat(SummaryFigureStripper.strip("  [1] ")).isEmpty();
    }

    @Test
    void theFallbacksListKeepsItsLabelsWithoutAnyFigure() {
        String fallback = """
                I couldn't confirm every figure in my answer, so here is what the data returned:
                - August 2026 · income 4,100.00 · spending 2,345.67 · net 1,754.33 [1]
                - Dining · August 2026 · money out 340.63 · money in 0.00 · 12 transactions [2]""";

        String stripped = SummaryFigureStripper.strip(fallback);

        assertThat(stripped).contains("August 2026 · income [amount] · spending [amount] · net [amount]")
                .contains("[number] transactions").doesNotContain("4,100").doesNotContain("340.63")
                .doesNotContain("[1]").doesNotContain("[2]");
        assertThat(FigureExtractor.extract(stripped)).isEmpty();
    }
}
