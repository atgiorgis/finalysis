package com.finalysis.chat.eval;

import com.finalysis.chat.AnswerSource;
import com.finalysis.chat.LimitHit;
import com.finalysis.chat.eval.ChatEvalResults.Observation;
import com.finalysis.chat.eval.ChatEvalResults.QuestionResult;
import com.finalysis.chat.eval.ChatEvalResults.TurnResult;
import com.finalysis.chat.eval.ChatEvalSet.Expect;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The run's figures (docs/plans/phase-6.md §13.3, step 8), counted in Java from the scored turns. Rates are percents
 * with one decimal (half-even), null when nothing was counted.
 *
 * @param all         every question
 * @param byCategory  per category, in the set's order
 * @param userWritten the owner's own questions only
 * @param limitStopped turns a limit stopped that are scored as limit-stopped (step 8d): never correct
 */
record ChatEvalMetrics(Totals all, Map<String, Totals> byCategory, Totals userWritten, Grounding grounding,
                       Refusals refusals, Injection injection, Citations citations, Map<LimitHit, Integer> limits,
                       int limitStopped, Usage usage, Latency latency) {

    /**
     * @param fallbacksCorrect correct turns whose shown answer was the Java fallback (counted in turnsCorrect too)
     * @param toolChoice       turns whose calls match an expected tool set
     */
    record Totals(int questions, int questionsCorrect, int turns, int turnsCorrect, int fallbacksCorrect,
                  int toolChoice, BigDecimal questionsCorrectPct, BigDecimal turnsCorrectPct,
                  BigDecimal toolChoicePct) {
    }

    /**
     * @param answered         turns that showed an answer
     * @param firstTry         grounded on the model's first answer (no retry)
     * @param finalModel       the model's answer shown, after the retry or not
     * @param fallbacks        the Java fallback shown
     * @param failed           no answer: a failed model call or a refused request
     * @param bareYearRetries  retries whose listed figures include a bare year (§15)
     * @param onlyBareYears    retries caused by bare years alone
     */
    record Grounding(int answered, int firstTry, int finalModel, int fallbacks, int failed, int retries,
                     int bareYearRetries, int onlyBareYears, BigDecimal firstTryPct, BigDecimal finalModelPct) {
    }

    /**
     * Refusals over every turn: one is detected when the answer has no figure and refuses.
     *
     * @param falseRefusals refusals of answer turns that expected figures
     */
    record Refusals(int expected, int detected, int correct, int falseRefusals, BigDecimal precisionPct,
                    BigDecimal recallPct) {
    }

    /** @param forbiddenShown turns in the whole run that showed a forbidden figure */
    record Injection(int questions, int correct, int forbiddenShown) {
    }

    /** Turns that expected citations, and those whose citations held every expected row. */
    record Citations(int expected, int found) {
    }

    /**
     * @param inputTokens       input tokens outside the cached prefix (step 8c)
     * @param cacheReadTokens   cached-prefix tokens read
     * @param cacheWriteTokens  cached-prefix tokens written
     * @param cost              the app's estimates summed over the turns (dollars, six decimals)
     * @param callsWithoutUsage model calls that reported no usage: the app counts each at its worst case
     */
    record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
                 int callsWithoutUsage, BigDecimal cost, int rounds, int toolCalls) {

        /** Every input token the model read: outside the cached prefix, read from the cache, written to it. */
        long allInputTokens() {
            return inputTokens + cacheReadTokens + cacheWriteTokens;
        }
    }

    record Latency(long meanTurnMillis, long medianTurnMillis, long maxTurnMillis, long meanQuestionMillis,
                   long maxQuestionMillis) {
    }

    static ChatEvalMetrics of(List<QuestionResult> questions, List<String> categoryOrder) {
        Map<String, Totals> byCategory = new LinkedHashMap<>();
        for (String category : categoryOrder) {
            List<QuestionResult> in = questions.stream().filter(q -> q.category().equals(category)).toList();
            if (!in.isEmpty()) {
                byCategory.put(category, totals(in));
            }
        }
        List<TurnResult> turns = questions.stream().flatMap(q -> q.turns().stream()).toList();
        List<QuestionResult> injection = questions.stream().filter(q -> q.category().equals("INJECTION")).toList();
        return new ChatEvalMetrics(totals(questions), byCategory,
                totals(questions.stream().filter(QuestionResult::userWritten).toList()), grounding(turns),
                refusals(turns),
                new Injection(injection.size(), (int) injection.stream().filter(QuestionResult::correct).count(),
                        count(turns, t -> !t.score().forbidden().isEmpty())),
                new Citations(count(turns, t -> t.observed().citationsFound() != null),
                        count(turns, t -> Boolean.TRUE.equals(t.observed().citationsFound()))),
                limits(turns), count(turns, t -> t.score().limitStopped()), usage(turns), latency(questions, turns));
    }

    static Totals totals(List<QuestionResult> questions) {
        List<TurnResult> turns = questions.stream().flatMap(q -> q.turns().stream()).toList();
        int questionsCorrect = (int) questions.stream().filter(QuestionResult::correct).count();
        int turnsCorrect = count(turns, t -> t.score().correct());
        int toolChoice = count(turns, t -> t.score().toolChoice());
        int expectedTurns = questions.stream().mapToInt(QuestionResult::expectedTurns).sum();
        return new Totals(questions.size(), questionsCorrect, expectedTurns, turnsCorrect,
                count(turns, t -> t.score().correct() && t.observed().source() == AnswerSource.FALLBACK), toolChoice,
                pct(questionsCorrect, questions.size()), pct(turnsCorrect, expectedTurns),
                pct(toolChoice, turns.size()));
    }

    private static Grounding grounding(List<TurnResult> turns) {
        List<Observation> answered = turns.stream().map(TurnResult::observed).filter(Observation::answered).toList();
        int firstTry = (int) answered.stream().filter(o -> o.source() == AnswerSource.MODEL && !o.retried()).count();
        int finalModel = (int) answered.stream().filter(o -> o.source() == AnswerSource.MODEL).count();
        int fallbacks = (int) answered.stream().filter(o -> o.source() == AnswerSource.FALLBACK).count();
        List<Observation> retried = turns.stream().map(TurnResult::observed).filter(Observation::retried).toList();
        int bareYear = (int) retried.stream()
                .filter(o -> o.retryFigures().stream().anyMatch(ChatEvalScoring::bareYear)).count();
        int onlyBareYears = (int) retried.stream().filter(o -> !o.retryFigures().isEmpty()
                && o.retryFigures().stream().allMatch(ChatEvalScoring::bareYear)).count();
        return new Grounding(answered.size(), firstTry, finalModel, fallbacks, turns.size() - answered.size(),
                retried.size(), bareYear, onlyBareYears, pct(firstTry, answered.size()),
                pct(finalModel, answered.size()));
    }

    private static Refusals refusals(List<TurnResult> turns) {
        int expected = count(turns, t -> t.expect() == Expect.REFUSAL);
        int detected = count(turns, t -> t.score().refusal());
        int correct = count(turns, t -> t.expect() == Expect.REFUSAL && t.score().refusal());
        int falseRefusals = count(turns, t -> t.expect() == Expect.ANSWER && t.score().refusal()
                && !t.score().correct());
        return new Refusals(expected, detected, correct, falseRefusals, pct(correct, detected), pct(correct, expected));
    }

    private static Map<LimitHit, Integer> limits(List<TurnResult> turns) {
        Map<LimitHit, Integer> limits = new EnumMap<>(LimitHit.class);
        turns.stream().map(t -> t.observed().limit()).filter(l -> l != null)
                .forEach(l -> limits.merge(l, 1, Integer::sum));
        return limits;
    }

    private static Usage usage(List<TurnResult> turns) {
        long input = 0;
        long output = 0;
        long cacheRead = 0;
        long cacheWrite = 0;
        int withoutUsage = 0;
        int rounds = 0;
        int toolCalls = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (TurnResult turn : turns) {
            Observation o = turn.observed();
            input += o.inputTokens() == null ? 0 : o.inputTokens();
            output += o.outputTokens() == null ? 0 : o.outputTokens();
            cacheRead += o.cacheReadTokens();
            cacheWrite += o.cacheWriteTokens();
            withoutUsage += o.callsWithoutUsage();
            rounds += o.rounds();
            toolCalls += o.tools().size();
            cost = cost.add(o.cost() == null ? BigDecimal.ZERO : o.cost());
        }
        return new Usage(input, output, cacheRead, cacheWrite, withoutUsage, cost.setScale(6, RoundingMode.HALF_EVEN),
                rounds, toolCalls);
    }

    private static Latency latency(List<QuestionResult> questions, List<TurnResult> turns) {
        List<Long> perTurn = new ArrayList<>(turns.stream().map(t -> t.observed().millis()).sorted().toList());
        List<Long> perQuestion = questions.stream().map(QuestionResult::millis).toList();
        return new Latency(mean(perTurn), perTurn.isEmpty() ? 0 : perTurn.get((perTurn.size() - 1) / 2),
                perTurn.stream().mapToLong(Long::longValue).max().orElse(0), mean(perQuestion),
                perQuestion.stream().mapToLong(Long::longValue).max().orElse(0));
    }

    private static long mean(List<Long> values) {
        return values.isEmpty() ? 0 : Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private static int count(List<TurnResult> turns, Predicate<TurnResult> which) {
        return (int) turns.stream().filter(which).count();
    }

    static BigDecimal pct(int part, int whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1,
                RoundingMode.HALF_EVEN);
    }
}
