package com.finalysis.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.chat.eval.SearchSweep.AtFloor;
import com.finalysis.chat.eval.SearchSweep.Candidate;
import com.finalysis.chat.eval.SearchSweep.FloorSummary;
import com.finalysis.chat.eval.SearchSweep.QueryResult;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The search-floor sweep's arithmetic (step 8) on hand-made candidates; every count by hand. */
class SearchSweepTest {

    private static Candidate name(String merchant) {
        return new Candidate(merchant, "NAME", null);
    }

    private static Candidate meaning(String merchant, String similarity) {
        return new Candidate(merchant, "MEANING", new BigDecimal(similarity));
    }

    /** "rideshare": a name match, then LYFT at 0.52, a coffee shop at 0.41, UBER at 0.36. */
    private static final List<Candidate> RIDESHARE = List.of(name("RIDESHARE CO-OP"), meaning("LYFT RIDE", "0.52"),
            meaning("BLUE BEAN CAFE", "0.41"), meaning("UBER TRIP", "0.36"));

    @Test
    void theFloorsAreThirtyToSixtyInStepsOfFive() {
        assertThat(SearchSweep.FLOORS).extracting(BigDecimal::toPlainString)
                .containsExactly("0.30", "0.35", "0.40", "0.45", "0.50", "0.55", "0.60");
    }

    @Test
    void aHigherFloorKeepsNameMatchesAndDropsMeaningHitsBelowIt() {
        List<String> targets = List.of("LYFT", "UBER");

        AtFloor low = SearchSweep.at(new BigDecimal("0.35"), RIDESHARE, targets);
        assertThat(low.candidates()).containsExactly("RIDESHARE CO-OP", "LYFT RIDE", "BLUE BEAN CAFE", "UBER TRIP");
        assertThat(low.found()).containsExactly("LYFT", "UBER");
        assertThat(low.others()).isEqualTo(2);
        assertThat(low.allFound(targets)).isTrue();

        AtFloor mid = SearchSweep.at(new BigDecimal("0.40"), RIDESHARE, targets);
        assertThat(mid.candidates()).containsExactly("RIDESHARE CO-OP", "LYFT RIDE", "BLUE BEAN CAFE");
        assertThat(mid.found()).containsExactly("LYFT");
        assertThat(mid.allFound(targets)).isFalse();

        AtFloor high = SearchSweep.at(new BigDecimal("0.60"), RIDESHARE, targets);
        assertThat(high.candidates()).containsExactly("RIDESHARE CO-OP");
        assertThat(high.found()).isEmpty();
        assertThat(high.others()).isEqualTo(1);
    }

    @Test
    void aSimilarityEqualToTheFloorIsKeptAndTargetsMatchIgnoringCase() {
        AtFloor at = SearchSweep.at(new BigDecimal("0.52"), RIDESHARE, List.of("lyft"));

        assertThat(at.candidates()).contains("LYFT RIDE");
        assertThat(at.found()).containsExactly("lyft");
    }

    @Test
    void aQueryIsWorkedOutAtEveryFloor() {
        QueryResult result = SearchSweep.query("d-s01", "rideshare", List.of("LYFT", "UBER"), "OK", RIDESHARE);

        assertThat(result.floors()).hasSize(7);
        assertThat(result.floors()).extracting(a -> a.candidates().size()).containsExactly(4, 4, 3, 2, 2, 1, 1);
        assertThat(result.floors()).extracting(a -> a.found().size()).containsExactly(2, 2, 1, 1, 1, 0, 0);
    }

    @Test
    void theSummaryCountsQueriesQuestionsTargetsAndNoise() {
        QueryResult rideshare = SearchSweep.query("d-s01", "rideshare", List.of("LYFT", "UBER"), "OK", RIDESHARE);
        QueryResult taxi = SearchSweep.query("d-s01", "taxi", List.of("LYFT", "UBER"), "OK",
                List.of(meaning("UBER TRIP", "0.58"), meaning("LYFT RIDE", "0.47")));
        QueryResult pharmacy = SearchSweep.query("d-s04", "pharmacy", List.of("CVS"), "OK",
                List.of(meaning("CVS PHARMACY", "0.44"), meaning("SAFEWAY", "0.31")));

        List<FloorSummary> summary = SearchSweep.summarize(List.of(rideshare, taxi, pharmacy));

        FloorSummary at30 = summary.getFirst();
        assertThat(at30.queries()).isEqualTo(3);
        assertThat(at30.queriesAllFound()).isEqualTo(3);
        assertThat(at30.questions()).isEqualTo(2);
        assertThat(at30.questionsAllFound()).isEqualTo(2);
        assertThat(at30.targets()).isEqualTo(5);
        assertThat(at30.targetsFound()).isEqualTo(5);
        assertThat(at30.candidates()).isEqualTo(8);
        assertThat(at30.others()).isEqualTo(3);

        FloorSummary at45 = summary.get(3);
        assertThat(at45.queriesAllFound()).as("taxi only").isEqualTo(1);
        assertThat(at45.questionsAllFound()).as("d-s01 through taxi; CVS lost at 0.45").isEqualTo(1);
        assertThat(at45.targetsFound()).isEqualTo(1 + 2);
        assertThat(at45.others()).isEqualTo(1);

        assertThat(summary.get(4).questionsAllFound()).as("0.50: taxi loses LYFT").isZero();
    }

    @Test
    void theHighestLosslessFloorKeepsEveryTargetFoundAtTheLowest() {
        QueryResult taxi = SearchSweep.query("d-s01", "taxi", List.of("LYFT", "UBER"), "OK",
                List.of(meaning("UBER TRIP", "0.58"), meaning("LYFT RIDE", "0.47")));
        QueryResult pharmacy = SearchSweep.query("d-s04", "pharmacy", List.of("CVS"), "OK",
                List.of(meaning("CVS PHARMACY", "0.44")));
        QueryResult missed = SearchSweep.query("d-s06", "ramen", List.of("NAKAMURA"), "OK",
                List.of(meaning("BLUE BEAN CAFE", "0.33")));

        assertThat(SearchSweep.highestLossless(List.of(taxi, pharmacy, missed)))
                .as("CVS is lost at 0.45; the missed query never counts").isEqualByComparingTo("0.40");
        assertThat(SearchSweep.highestLossless(List.of(taxi))).isEqualByComparingTo("0.45");
        assertThat(SearchSweep.highestLossless(List.of(missed))).isNull();
    }

    @Test
    void aRefusedSearchHasNoCandidates() {
        QueryResult refused = SearchSweep.query("d-s01", "x", List.of("LYFT"), "INVALID_ARGUMENTS", List.of());

        assertThat(refused.floors()).allMatch(a -> a.candidates().isEmpty() && a.found().isEmpty());
    }
}
