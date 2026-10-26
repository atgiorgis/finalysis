package com.finalysis.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.chat.eval.ChatEvalSet.Expect;
import com.finalysis.chat.eval.ChatEvalSet.FigureKind;
import com.finalysis.chat.eval.ChatEvalSet.Question;
import com.finalysis.support.ImportTestSupport;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The evaluation's typed reading of the question sets (step 8), and the sweep's dev-only queries. The holdout is read
 * here only to check that it parses whole and that each scoring note names a question shaped as the note says;
 * {@code ChatQuestionSetsTest} checks its contents.
 */
class ChatEvalSetTest {

    private static final Path CHAT = ImportTestSupport.SAMPLE_DATA.resolve("chat");

    @Test
    void theDevSetLoadsWhole() {
        ChatEvalSet dev = ChatEvalSet.load(CHAT.resolve("dev.yml"));

        assertThat(dev.name()).isEqualTo("dev");
        assertThat(dev.questions()).hasSize(60);
        assertThat(dev.questions().stream().mapToInt(q -> q.turns().size()).sum()).isEqualTo(68);
        assertThat(dev.userWritten()).isEmpty();
        assertThat(dev.datasets()).containsOnlyKeys("SAMPLE", "FIXTURE", "INJECTION");
        assertThat(dev.datasets().get("SAMPLE").today()).isEqualTo(LocalDate.of(2026, 10, 15));

        Question lookup = dev.question("d-l01");
        assertThat(lookup.turns().getFirst().figures().getFirst().getFirst().value()).isEqualByComparingTo("190.10");
        assertThat(lookup.turns().getFirst().figures().getFirst().getFirst().kind()).isEqualTo(FigureKind.MONEY);
        assertThat(dev.question("d-l05").turns().getFirst().figures().getFirst().getFirst().kind())
                .isEqualTo(FigureKind.PERCENT);
        assertThat(dev.question("d-i04").turns().getFirst().forbidden()).extracting(f -> f.text())
                .containsExactly("10.00");
        assertThat(dev.question("d-x02").turns().getFirst().expect()).isEqualTo(Expect.REFUSAL);
        assertThat(dev.question("d-m03").turns().get(1).citations()).singleElement()
                .satisfies(c -> assertThat(c.account()).isEqualTo("apex-visa"));
        assertThat(dev.question("d-m03").turns().get(1).dependsOnPrevious()).isTrue();
        assertThat(dev.question("d-s01").searchTargets()).containsExactly("LYFT", "UBER");
    }

    @Test
    void theHoldoutParsesWholeAndItsScoringNoteFitsItsQuestion() {
        ChatEvalSet holdout = ChatEvalSet.load(CHAT.resolve("holdout.yml"));

        assertThat(holdout.sha256()).isEqualTo(ChatEvalGuards.HOLDOUT_SHA256);
        assertThat(holdout.questions()).hasSize(58);
        assertThat(holdout.userWritten()).hasSize(10);
        assertThat(holdout.questions().stream().filter(holdout::userWritten)).hasSize(10);
        for (Map.Entry<String, List<java.util.Set<Integer>>> note : ChatEvalScoring.READINGS.entrySet()) {
            Question question = holdout.question(note.getKey());
            int alternatives = note.getValue().stream().mapToInt(java.util.Set::size).sum();
            assertThat(question.turns()).as(note.getKey()).hasSizeGreaterThan(1)
                    .allSatisfy(turn -> assertThat(turn.figures()).hasSize(alternatives));
        }
    }

    @Test
    void theSweepQueriesCoverTheDevSearchQuestionsOnlyAndNameNoTarget() {
        ChatEvalSet dev = ChatEvalSet.load(CHAT.resolve("dev.yml"));
        Map<String, List<String>> queries = SearchSweepRunner.loadQueries();

        assertThat(queries.keySet()).containsExactlyInAnyOrderElementsOf(dev.questions().stream()
                .filter(q -> !q.searchTargets().isEmpty()).map(Question::id).toList());
        queries.forEach((id, texts) -> {
            assertThat(texts).as(id).isNotEmpty();
            for (String text : texts) {
                assertThat(text.length()).as(text).isBetween(2, 60);
                for (String target : dev.question(id).searchTargets()) {
                    assertThat(text.toUpperCase(Locale.ROOT)).as(id).doesNotContain(target);
                }
            }
        });
    }
}
