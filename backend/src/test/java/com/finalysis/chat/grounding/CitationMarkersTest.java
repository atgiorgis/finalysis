package com.finalysis.chat.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link CitationMarkers} (docs/plans/phase-6.md §6): issued refs kept, anything else in marker form removed. */
class CitationMarkersTest {

    private static final Set<String> ISSUED = Set.of("t1", "t2", "c1", "a1", "s1");

    @Test
    void keptMarkersAndTheirOrder() {
        CitationMarkers.Parsed parsed = CitationMarkers.parse("Rent [c1] and groceries [t2, t1]; again [c1].",
                ISSUED::contains);

        assertThat(parsed.text()).isEqualTo("Rent [c1] and groceries [t2, t1]; again [c1].");
        assertThat(parsed.refs()).containsExactly("c1", "t2", "t1");
        assertThat(parsed.dropped()).isZero();
        assertThat(parsed.markers().get(1).start()).isEqualTo(parsed.text().indexOf("[t2"));
    }

    @Test
    void unknownRefsNumbersAndOtherLettersAreDropped() {
        CitationMarkers.Parsed parsed = CitationMarkers.parse("A [t9]. B [7]. C [T1]. D [t1, x3, c1]. E [ c1 ].",
                ISSUED::contains);

        assertThat(parsed.text()).isEqualTo("A. B. C. D [t1, c1]. E [c1].");
        assertThat(parsed.dropped()).isEqualTo(4);
    }

    @Test
    void textThatIsNotMarkerShapedIsLeftAlone() {
        String text = "Ranges [2026-03 to 2026-04], amounts [$45.00] and lists [a, b] stay.";

        assertThat(CitationMarkers.parse(text, ISSUED::contains).text()).isEqualTo(text);
    }

    @Test
    void renumberingFollowsTheGivenNumbers() {
        String text = "Rent [c1] and groceries [t2, t1]; again [c1], and [s1].";

        assertThat(CitationMarkers.renumber(text, Map.of("c1", 1, "t2", 2, "t1", 3)))
                .isEqualTo("Rent [1] and groceries [2, 3]; again [1], and.");
    }

    // ------------------------------------------------------------------ Unicode marker forms (step 9a-3)

    /** gpt-oss writes lenticular brackets and a zero-width space inside a marker; both are the marker, as ASCII. */
    @Test
    void lenticularFullWidthAndZeroWidthMarkersAreMarkers() {
        CitationMarkers.Parsed parsed = CitationMarkers.parse(
                "Rent\u3010c1\u3011 and groceries \uff3bt2\uff0c t1\uff3d; again [\u200bc1] and [c1\u200b].",
                ISSUED::contains);

        assertThat(parsed.text()).isEqualTo("Rent[c1] and groceries [t2, t1]; again [c1] and [c1].");
        assertThat(parsed.refs()).containsExactly("c1", "t2", "t1");
        assertThat(parsed.dropped()).isZero();
        assertThat(parsed.markers().get(1).start()).isEqualTo(parsed.text().indexOf("[t2"));
    }

    @Test
    void unicodeMarkersOfRefsNeverIssuedAreDroppedWithTheirSpace() {
        CitationMarkers.Parsed parsed = CitationMarkers.parse(
                "A\u202f\u3010t9\u3011. B \u30107\u3011. D\u202f\u3010t1, x3,\u202fc1\u3011. E [\u200b c1\u202f].",
                ISSUED::contains);

        assertThat(parsed.text()).isEqualTo("A. B. D\u202f[t1, c1]. E [c1].");
        assertThat(parsed.dropped()).isEqualTo(3);
    }

    /** Only the marker groups change: every other character, Unicode spaces included, is kept as written. */
    @Test
    void textAroundAUnicodeMarkerIsKeptAsWritten() {
        String text = "You spent\u202f$302.17 on utilities in May\u202f2026.\u202f\u3010c1\u3011";

        assertThat(CitationMarkers.parse(text, ISSUED::contains).text())
                .isEqualTo("You spent\u202f$302.17 on utilities in May\u202f2026.\u202f[c1]");
        assertThat(CitationMarkers.renumber(text, Map.of("c1", 1)))
                .isEqualTo("You spent\u202f$302.17 on utilities in May\u202f2026.\u202f[1]");
    }

    @Test
    void unicodeBracketsAroundTextThatIsNotMarkerShapedStay() {
        String text = "A note \u3010see the table\u3011 and \u3010$45.00\u3011 stay.";

        assertThat(CitationMarkers.parse(text, ISSUED::contains).text()).isEqualTo(text);
    }

    @Test
    void renumberingReadsUnicodeMarkers() {
        assertThat(CitationMarkers.renumber("Rent\u3010c1\u3011 and \uff3bt2\uff0c t1\uff3d, and [\u200bs1].",
                Map.of("c1", 1, "t2", 2, "t1", 3))).isEqualTo("Rent[1] and [2, 3], and.");
    }
}
