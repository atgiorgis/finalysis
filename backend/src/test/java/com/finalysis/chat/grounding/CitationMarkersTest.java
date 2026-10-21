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
}
