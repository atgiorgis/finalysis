package com.finalysis.chat.eval;

import com.finalysis.chat.AnswerSource;
import com.finalysis.chat.LimitHit;
import com.finalysis.chat.eval.ChatEvalScoring.TurnScore;
import com.finalysis.chat.eval.ChatEvalSet.Expect;
import com.finalysis.chat.grounding.GroundingStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** What a chat evaluation run observed and scored (docs/plans/phase-6.md §13.3, step 8). In memory, then the report. */
final class ChatEvalResults {

    private ChatEvalResults() {
    }

    /**
     * One problem of an invalid tool call, as the model read it (step 8b): the tool (or {@code (unknown)}), the
     * parameter (for an invented one the name the model sent, step 8e, or {@code (unknown)} when it can't be read;
     * {@code (call)} for the whole call) and the
     * {@code ToolProblem}.
     */
    record InvalidArgument(String tool, String parameter, String problem) {
    }

    /** One tool call of a turn: its name (null for an invented one), status and rows sent. */
    record ToolUse(String name, String status, int rows) {
    }

    /**
     * What one turn did.
     *
     * @param answer         the shown text with its citation markers; null when the turn failed
     * @param problem        why there is no answer: the problem type of a refused request, or the failed call's
     *                       source; null otherwise
     * @param retryFigures   the figures the grounding retry listed, as written (empty without a retry)
     * @param rejectedAnswer the first answer, when the retry replaced it: never shown to a user, kept for review
     * @param searchQueries  the search texts the model sent to {@code search_transactions}
     * @param invalidArguments every problem of the turn's invalid tool calls, in order
     * @param inputTokens    input tokens outside the cached prefix, as reported (step 8c)
     * @param cacheReadTokens  cached-prefix tokens read; 0 without caching
     * @param cacheWriteTokens cached-prefix tokens written; 0 without caching
     * @param cost           the app's estimate in dollars (the price file's price for the model)
     * @param millis         wall time of the turn, as the harness measured it
     */
    record Observation(String answer, String problem, AnswerSource source, GroundingStatus grounding, boolean retried,
                       int figuresChecked, int ungrounded, List<ToolUse> tools, LimitHit limit, int rounds,
                       Integer inputTokens, Integer outputTokens, int cacheReadTokens, int cacheWriteTokens,
                       int callsWithoutUsage, BigDecimal cost, long millis,
                       List<String> retryFigures, String rejectedAnswer, List<String> searchQueries,
                       Boolean citationsFound, List<InvalidArgument> invalidArguments) {

        Observation {
            tools = List.copyOf(tools);
            invalidArguments = List.copyOf(invalidArguments);
            retryFigures = List.copyOf(retryFigures);
            searchQueries = List.copyOf(searchQueries);
        }

        boolean answered() {
            return answer != null;
        }
    }

    record TurnResult(int turn, String ask, Expect expect, String note, Observation observed, TurnScore score) {
    }

    /** A question with every turn that ran; a question is correct when all of its turns are. */
    record QuestionResult(String id, String category, String author, boolean userWritten, String dataset,
                          int expectedTurns, List<TurnResult> turns) {

        QuestionResult {
            turns = List.copyOf(turns);
        }

        boolean correct() {
            return turns.size() == expectedTurns && turns.stream().allMatch(t -> t.score().correct());
        }

        long millis() {
            return turns.stream().mapToLong(t -> t.observed().millis()).sum();
        }
    }

    /**
     * The search index built for a dataset before its questions (counts of categorization's refresh).
     *
     * @param remaining merchants left without a vector (over the per-run cap)
     */
    record IndexBuild(String dataset, String status, int merchants, int embedded, int cached, int calls,
                      int remaining) {
    }

    /**
     * A whole run.
     *
     * @param settings        every chat and AI setting of the context, by property name
     * @param promptCaching   whether chat cached its fixed prefix, as the app decided it at the end of the run
     *                        ({@code ChatModelClient.PromptCaching}: {@code ON}, or why not; step 8c)
     * @param ledgerCost      every chat call's cost as the usage ledger summed it (turns and conversation summaries)
     * @param stopped         why the run stopped before the last question, or null when it finished
     * @param maxTurnCost     the turn cost limit the run used, in dollars as written ({@code 0} is off; step 8d)
     * @param maxTurnCostOverridden whether {@code -Deval.maxTurnCost} set it for this run
     */
    /** @param target where the questions went (Phase 9 step 9b-1): Bedrock, or an Ollama host on its path */
    record Run(String set, String setSha256, int setVersion, String model, String promptCaching, String promptVersion,
               String maxTurnCost, boolean maxTurnCostOverridden,
               String systemPromptSha256, String pricesSource, Map<String, String> todayByDataset,
               Map<String, String> settings, List<IndexBuild> index, List<QuestionResult> questions,
               BigDecimal ledgerCost, String stopped, long wallMillis, ChatEvalTarget target) {

        Run {
            todayByDataset = Map.copyOf(todayByDataset);
            settings = Map.copyOf(settings);
            index = List.copyOf(index);
            questions = List.copyOf(questions);
        }

        /** A Bedrock run, as every run before Phase 9 was. */
        Run(String set, String setSha256, int setVersion, String model, String promptCaching, String promptVersion,
            String maxTurnCost, boolean maxTurnCostOverridden, String systemPromptSha256, String pricesSource,
            Map<String, String> todayByDataset, Map<String, String> settings, List<IndexBuild> index,
            List<QuestionResult> questions, BigDecimal ledgerCost, String stopped, long wallMillis) {
            this(set, setSha256, setVersion, model, promptCaching, promptVersion, maxTurnCost, maxTurnCostOverridden,
                    systemPromptSha256, pricesSource, todayByDataset, settings, index, questions, ledgerCost, stopped,
                    wallMillis, ChatEvalTarget.BEDROCK);
        }
    }
}
