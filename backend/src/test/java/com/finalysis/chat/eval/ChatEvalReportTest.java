package com.finalysis.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.chat.AnswerSource;
import com.finalysis.chat.eval.ChatEvalGuards.GitState;
import com.finalysis.chat.eval.ChatEvalResults.IndexBuild;
import com.finalysis.chat.eval.ChatEvalResults.Observation;
import com.finalysis.chat.eval.ChatEvalResults.QuestionResult;
import com.finalysis.chat.eval.ChatEvalResults.Run;
import com.finalysis.chat.eval.ChatEvalResults.ToolUse;
import com.finalysis.chat.eval.ChatEvalResults.TurnResult;
import com.finalysis.chat.eval.ChatEvalScoring.Shown;
import com.finalysis.chat.eval.ChatEvalSet.Dataset;
import com.finalysis.chat.eval.ChatEvalSet.Expect;
import com.finalysis.chat.eval.ChatEvalSet.ExpectedFigure;
import com.finalysis.chat.eval.ChatEvalSet.Question;
import com.finalysis.chat.eval.ChatEvalSet.Turn;
import com.finalysis.chat.grounding.GroundingStatus;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The chat evaluation's results files (step 8): their names, what they record, and every answer for reading. */
class ChatEvalReportTest {

    private static final GitState GIT = new GitState("0123456789abcdef0123456789abcdef01234567", false);
    private static final Instant STARTED = Instant.parse("2026-10-21T10:15:30Z");

    private static Turn turn(String ask, String figure) {
        return new Turn(ask, Expect.ANSWER, List.of(Set.of("get_category_totals")),
                List.of(List.of(ExpectedFigure.parse(figure))), List.of(), List.of(), "a note", false);
    }

    private static ChatEvalSet set() {
        return new ChatEvalSet("holdout", 1, "abc", Map.of("SAMPLE", new Dataset("SAMPLE", LocalDate.of(2026, 10, 15))),
                List.of("h-u01"), List.of(
                new Question("h-l01", "assistant", "LOOKUP", "SAMPLE", List.of(), List.of(turn("dining?", "190.10"))),
                new Question("h-u01", "user", "LOOKUP", "SAMPLE", List.of(), List.of(turn("groceries?", "477.94")))));
    }

    private static TurnResult result(Turn turn, String answer, boolean retried, String rejected) {
        Observation observed = new Observation(answer, null, AnswerSource.MODEL, GroundingStatus.GROUNDED, retried, 1,
                0, List.of(new ToolUse("get_category_totals", "OK", 1)), null, retried ? 3 : 2, 1000, 100, 0,
                new BigDecimal("0.001500"), 1500, retried ? List.of("2026") : List.of(), rejected, List.of(), null,
                List.of());
        return new TurnResult(1, turn.ask(), turn.expect(), turn.note(), observed,
                ChatEvalScoring.score(turn, new Shown(answer, AnswerSource.MODEL, Set.of("get_category_totals"), null),
                        List.of()));
    }

    private static ChatEvalReport report() {
        ChatEvalSet set = set();
        List<QuestionResult> questions = List.of(
                new QuestionResult("h-l01", "LOOKUP", "assistant", false, "SAMPLE", 1, List.of(result(
                        set.question("h-l01").turns().getFirst(), "You spent $190.10 on dining [1].", false, null))),
                new QuestionResult("h-u01", "LOOKUP", "user", true, "SAMPLE", 1, List.of(result(
                        set.question("h-u01").turns().getFirst(), "You spent $400.00 on groceries.\nThat's all.", true,
                        "So far in 2026 you spent $400.00."))));
        Run run = new Run("holdout", "abc", 1, "test-model", "chat-3", "def", "the price file",
                Map.of("SAMPLE", "2026-10-15"), Map.of("finalysis.chat.max-rounds", "5"),
                List.of(new IndexBuild("SAMPLE", "OK", 40, 40, 0, 1, 0)), questions, new BigDecimal("0.003000"), null,
                5000);
        return ChatEvalReport.of(GIT, STARTED, set, run);
    }

    @Test
    void theFilesAreNamedBySetCommitAndTimeAndNeverOverwritten(@TempDir Path dir) throws Exception {
        ChatEvalReport report = report();

        assertThat(report.fileStem()).isEqualTo("chat-holdout-0123456789ab-20261021T101530Z");
        assertThat(report.fileStem()).startsWith(ChatEvalGuards.HOLDOUT_RESULTS_PREFIX);
        List<Path> files = report.write(dir);
        assertThat(files).extracting(p -> p.getFileName().toString())
                .containsExactly(report.fileStem() + ".json", report.fileStem() + ".md");
        assertThatThrownBy(() -> report.write(dir)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");

        String json = Files.readString(files.getFirst());
        assertThat(json).contains("\"commit\" : \"" + GIT.commit() + "\"").contains("\"model\" : \"test-model\"")
                .contains("finalysis.chat.max-rounds").contains("\"questionsCorrect\" : 1");
    }

    @Test
    void theMarkdownRecordsTheRunAndShowsEveryAnswer() {
        String md = report().markdown();

        assertThat(md).contains("# Chat eval: holdout, test-model")
                .contains("Commit: `" + GIT.commit() + "` (clean)")
                .contains("Chat model: `test-model`; prompt `chat-3`")
                .contains("SAMPLE 2026-10-15")
                .contains("| 1 / 2 (50.0%) |")
                .contains("`finalysis.chat.max-rounds`: `5`")
                .contains("> You spent $190.10 on dining [1].")
                .contains("> You spent $400.00 on groceries.\n> That's all.")
                .contains("First answer (not shown):\n\n> So far in 2026 you spent $400.00.")
                .contains("retried for [2026]")
                .contains("- Expected: 477.94 (a note)")
                .contains("**h-u01 turn 1**: missing figures");
    }

    @Test
    void theOwnersQuestionsHaveTheirOwnSection() {
        String md = report().markdown();
        String yours = md.substring(md.indexOf("## Your questions"), md.indexOf("## Grounding, refusals, tools"));

        assertThat(yours).contains("Your 1 questions: 0 correct (0.0%)").contains("### h-u01 (LOOKUP, yours)")
                .doesNotContain("h-l01");
    }

    @Test
    void aSetWithoutUserQuestionsSaysSo() {
        ChatEvalSet set = set();
        Run run = new Run("dev", "abc", 1, "m", "chat-3", "def", "p", Map.of(), Map.of(), List.of(), List.of(),
                BigDecimal.ZERO, "run budget reached", 0);

        String md = ChatEvalReport.of(GIT, STARTED, set, run).markdown();
        assertThat(md).contains("This set has no user-written questions.").contains("**Stopped early:** run budget");
    }
}
