package com.finalysis.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.chat.AnswerSource;
import com.finalysis.chat.LimitHit;
import com.finalysis.chat.eval.ChatEvalResults.Observation;
import com.finalysis.chat.eval.ChatEvalResults.QuestionResult;
import com.finalysis.chat.eval.ChatEvalResults.ToolUse;
import com.finalysis.chat.eval.ChatEvalResults.TurnResult;
import com.finalysis.chat.eval.ChatEvalScoring.TurnScore;
import com.finalysis.chat.eval.ChatEvalSet.Expect;
import com.finalysis.chat.grounding.GroundingStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The run's figures (docs/plans/phase-6.md §13.3, step 8) from hand-made scored turns: every count by hand. */
class ChatEvalMetricsTest {

    private static Observation observed(AnswerSource source, boolean retried, List<String> retryFigures,
                                        LimitHit limit, Integer input, Integer output, String cost, long millis) {
        return new Observation(source == AnswerSource.ERROR ? null : "text", source == AnswerSource.ERROR ? "ERROR"
                : null, source, source == AnswerSource.MODEL ? GroundingStatus.GROUNDED : null, retried, 1, 0,
                List.of(new ToolUse("get_month_totals", "OK", 1)), limit, retried ? 3 : 2, input, output,
                input == null ? 2 : 0, cost == null ? null : new BigDecimal(cost), millis, retryFigures, null,
                List.of(), null);
    }

    private static TurnScore score(boolean correct, boolean refusal, boolean toolChoice, List<String> forbidden) {
        return new TurnScore(correct, "", List.of(), List.of(), forbidden, refusal, false, toolChoice);
    }

    private static TurnResult turn(Expect expect, Observation observed, TurnScore score) {
        return new TurnResult(1, "q", expect, null, observed, score);
    }

    private static List<QuestionResult> run() {
        Observation firstTry = observed(AnswerSource.MODEL, false, List.of(), null, 1000, 100, "0.001500", 2000);
        Observation retriedYear = observed(AnswerSource.MODEL, true, List.of("2026"), null, 2000, 200, "0.003000",
                4000);
        Observation fallback = observed(AnswerSource.FALLBACK, true, List.of("2026", "$45.10"), LimitHit.ROUNDS, 3000,
                300, "0.004500", 6000);
        Observation failed = observed(AnswerSource.ERROR, false, List.of(), null, null, null, null, 1000);
        return List.of(
                new QuestionResult("d-l01", "LOOKUP", "assistant", false, "SAMPLE", 1,
                        List.of(turn(Expect.ANSWER, firstTry, score(true, false, true, List.of())))),
                new QuestionResult("d-l02", "LOOKUP", "assistant", false, "SAMPLE", 1,
                        List.of(turn(Expect.ANSWER, fallback, score(true, false, false, List.of())))),
                new QuestionResult("h-u01", "LOOKUP", "user", true, "SAMPLE", 2, List.of(
                        turn(Expect.ANSWER, retriedYear, score(true, false, true, List.of())),
                        turn(Expect.ANSWER, firstTry, score(false, true, true, List.of())))),
                new QuestionResult("d-i01", "INJECTION", "assistant", false, "INJECTION", 1,
                        List.of(turn(Expect.ANSWER, firstTry, score(false, false, true, List.of("999.99"))))),
                new QuestionResult("d-x01", "CANT_ANSWER", "assistant", false, "SAMPLE", 1,
                        List.of(turn(Expect.REFUSAL, firstTry, score(true, true, true, List.of())))),
                new QuestionResult("d-x02", "CANT_ANSWER", "assistant", false, "SAMPLE", 2,
                        List.of(turn(Expect.REFUSAL, failed, score(false, false, true, List.of())))));
    }

    @Test
    void totalsCountQuestionsTurnsAndToolChoice() {
        ChatEvalMetrics metrics = ChatEvalMetrics.of(run(), List.of("LOOKUP", "INJECTION", "CANT_ANSWER"));

        assertThat(metrics.all().questions()).isEqualTo(6);
        assertThat(metrics.all().questionsCorrect()).as("d-l01, d-l02, d-x01").isEqualTo(3);
        assertThat(metrics.all().turns()).as("expected turns, a turn not run included").isEqualTo(8);
        assertThat(metrics.all().turnsCorrect()).isEqualTo(4);
        assertThat(metrics.all().fallbacksCorrect()).isEqualTo(1);
        assertThat(metrics.all().toolChoice()).isEqualTo(6);
        assertThat(metrics.all().questionsCorrectPct()).isEqualByComparingTo("50.0");
        assertThat(metrics.all().turnsCorrectPct()).isEqualByComparingTo("50.0");
        assertThat(metrics.all().toolChoicePct()).as("6 of the 7 turns run").isEqualByComparingTo("85.7");
        assertThat(metrics.byCategory()).containsOnlyKeys("LOOKUP", "INJECTION", "CANT_ANSWER");
        assertThat(metrics.byCategory().get("LOOKUP").questionsCorrect()).isEqualTo(2);
    }

    @Test
    void theUserWrittenQuestionsAreCountedApart() {
        ChatEvalMetrics.Totals user = ChatEvalMetrics.of(run(), List.of()).userWritten();

        assertThat(user.questions()).isEqualTo(1);
        assertThat(user.questionsCorrect()).isZero();
        assertThat(user.turnsCorrect()).isEqualTo(1);
    }

    @Test
    void groundingCountsFirstTryFinalFallbacksFailuresAndBareYearRetries() {
        ChatEvalMetrics.Grounding g = ChatEvalMetrics.of(run(), List.of()).grounding();

        assertThat(g.answered()).isEqualTo(6);
        assertThat(g.firstTry()).isEqualTo(4);
        assertThat(g.finalModel()).isEqualTo(5);
        assertThat(g.fallbacks()).isEqualTo(1);
        assertThat(g.failed()).isEqualTo(1);
        assertThat(g.retries()).isEqualTo(2);
        assertThat(g.bareYearRetries()).isEqualTo(2);
        assertThat(g.onlyBareYears()).isEqualTo(1);
        assertThat(g.firstTryPct()).isEqualByComparingTo("66.7");
        assertThat(g.finalModelPct()).isEqualByComparingTo("83.3");
    }

    @Test
    void refusalsInjectionAndLimits() {
        ChatEvalMetrics metrics = ChatEvalMetrics.of(run(), List.of());

        assertThat(metrics.refusals().expected()).isEqualTo(2);
        assertThat(metrics.refusals().detected()).isEqualTo(2);
        assertThat(metrics.refusals().correct()).isEqualTo(1);
        assertThat(metrics.refusals().falseRefusals()).isEqualTo(1);
        assertThat(metrics.refusals().precisionPct()).isEqualByComparingTo("50.0");
        assertThat(metrics.refusals().recallPct()).isEqualByComparingTo("50.0");
        assertThat(metrics.injection()).isEqualTo(new ChatEvalMetrics.Injection(1, 0, 1));
        assertThat(metrics.limits()).containsExactly(java.util.Map.entry(LimitHit.ROUNDS, 1));
    }

    @Test
    void usageAndLatencySumTheTurns() {
        ChatEvalMetrics metrics = ChatEvalMetrics.of(run(), List.of());

        assertThat(metrics.usage().inputTokens()).isEqualTo(1000 * 4 + 2000 + 3000);
        assertThat(metrics.usage().outputTokens()).isEqualTo(100 * 4 + 200 + 300);
        assertThat(metrics.usage().callsWithoutUsage()).isEqualTo(2);
        assertThat(metrics.usage().cost()).isEqualByComparingTo("0.013500");
        assertThat(metrics.usage().toolCalls()).isEqualTo(7);
        assertThat(metrics.latency().maxTurnMillis()).isEqualTo(6000);
        assertThat(metrics.latency().medianTurnMillis()).as("1000, 2000 x4, 4000, 6000").isEqualTo(2000);
        assertThat(metrics.latency().maxQuestionMillis()).isEqualTo(6000);
        assertThat(metrics.latency().meanQuestionMillis()).as("2000+6000+6000+2000+2000+1000 / 6").isEqualTo(3167);
    }

    @Test
    void noTurnsGiveNoRates() {
        ChatEvalMetrics metrics = ChatEvalMetrics.of(List.of(), List.of());

        assertThat(metrics.all().questionsCorrectPct()).isNull();
        assertThat(metrics.grounding().firstTryPct()).isNull();
        assertThat(metrics.refusals().precisionPct()).isNull();
    }
}
