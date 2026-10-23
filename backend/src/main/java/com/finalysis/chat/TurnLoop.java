package com.finalysis.chat;

import com.finalysis.ai.AiPrices;
import com.finalysis.chat.grounding.AnswerCheck;
import com.finalysis.chat.grounding.AnswerGrounding;
import com.finalysis.chat.grounding.FallbackAnswer;
import com.finalysis.chat.grounding.TurnEvidence;
import com.finalysis.chat.outbound.OutboundTurn;
import com.finalysis.chat.tool.CategoryNamesQuery;
import com.finalysis.chat.tool.ToolRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * The rounds of one turn (docs/plans/phase-6.md §1 step 3–4, §5.5, §8): the model routes, {@link ToolExecutor} runs
 * every tool call, and the answer is checked by {@link AnswerGrounding} before it can be shown.
 * <ol>
 *   <li>Before each round, the limits: rounds, wall time, input tokens (to fit the per-call cap, the history is
 *       shortened first, oldest first, then the oldest tool results of the turn are cut), and the cost, with the round's worst case (estimated input and the output cap)
 *       added to what the turn has spent. A limit ends the turn with the fallback.</li>
 *   <li>Tool calls are run in order and answered in one tool-response message. Past the tool call cap, or in the last
 *       round the turn has, they are not run ({@code SKIPPED_LIMIT}) and the model is told to answer.</li>
 *   <li>An answer is grounded or it isn't shown. Ungrounded (or cut at the output cap): one retry with the list of the
 *       ungrounded figures, unless a limit was hit; then the Java fallback ({@link FallbackAnswer}). A blank answer
 *       goes to the fallback directly.</li>
 * </ol>
 * A model call that fails ends the turn as an error. The loop never writes anything: the caller stores the turn.
 */
@Component
public class TurnLoop {

    /** The retry after an answer cut at the output cap; framed as the grounding retry is (step 9a). */
    static final String CUT_ANSWER_RETRY = AnswerGrounding.AUTOMATIC + "the answer was cut off at the length limit. "
            + "Write the answer to the user's question again from the start, briefly, using only figures from this "
            + "turn's tool results.";

    private final ChatModelClient client;
    private final ToolExecutor executor;
    private final AnswerGrounding grounding;
    private final ChatProperties limits;
    private final AiPrices prices;
    private final Clock clock;
    private final ToolRegistry registry;

    public TurnLoop(ChatModelClient client, ToolExecutor executor, AnswerGrounding grounding, ChatProperties limits,
                    AiPrices prices, Clock clock, ToolRegistry registry) {
        this.client = client;
        this.executor = executor;
        this.grounding = grounding;
        this.limits = limits;
        this.prices = prices;
        this.clock = clock;
        this.registry = registry;
    }

    /**
     * How the turn ended.
     *
     * @param source       {@code MODEL}, {@code FALLBACK} or {@code ERROR}
     * @param check        the shown model answer's check ({@code MODEL}); else the last answer checked, or null
     * @param fallback     the fallback's text with its ref markers ({@code FALLBACK}); else null
     * @param limit        the first limit reached, or null
     * @param error        the model call's failure ({@code ERROR}); else null
     * @param inputTokens  reported input tokens outside a cached prefix, summed; null when no call reported usage
     * @param cacheReadTokens  cached-prefix tokens read, summed (step 8c)
     * @param cacheWriteTokens cached-prefix tokens written, summed
     * @param inputForLimits input tokens counted against the per-turn cap: reported (cached prefix included, since the
     *                     model reads it all the same), or estimated for a call without usage
     * @param outputTokens reported output tokens summed; null when no call reported usage
     * @param cost         estimated cost; null when the model has no price
     */
    public record Result(AnswerSource source, AnswerCheck check, String fallback, boolean retried, LimitHit limit,
                         TurnEvidence evidence, int rounds, int toolCalls, Integer inputTokens, Integer outputTokens,
                         int cacheReadTokens, int cacheWriteTokens, long inputForLimits, int callsWithoutUsage,
                         BigDecimal cost, RuntimeException error) {
    }

    /** One turn's running state. */
    private final class Run {

        final OutboundTurn turn;
        final String question;
        final Collection<String> names;
        final List<String> categories;
        final int definitionChars;
        final List<Message> messages;
        final List<TurnEvidence.Result> results = new ArrayList<>();
        final Optional<AiPrices.Price> price = prices.of(client.modelId());
        final long started = clock.millis();
        int rounds;
        int toolCalls;
        Integer inputTokens;
        Integer outputTokens;
        int cacheReadTokens;
        int cacheWriteTokens;
        int callsWithoutUsage;
        long inputForLimits;
        BigDecimal cost = price.isPresent() ? BigDecimal.ZERO : null;
        LimitHit limit;
        boolean retried;
        AnswerCheck lastCheck;
        String cutResult;
        int historyMessages;

        Run(OutboundTurn turn, String question, Collection<String> names, List<Message> start, int historyMessages) {
            this.turn = turn;
            this.question = question;
            this.names = names;
            this.categories = CategoryNamesQuery.sorted(turn.categories());
            this.definitionChars = registry.definitions(categories).stream()
                    .mapToInt(d -> d.name().length() + d.description().length() + d.inputSchema().length()).sum();
            this.messages = new ArrayList<>(start);
            this.historyMessages = historyMessages;
        }

        TurnEvidence evidence() {
            return new TurnEvidence(question, results, turn.refs(), names);
        }

        void hit(LimitHit hit) {
            if (limit == null) {
                limit = hit;
            }
        }

        int toolResultsInPrompt() {
            return messages.stream().filter(m -> m instanceof ToolResponseMessage)
                    .mapToInt(m -> ((ToolResponseMessage) m).getResponses().size()).sum();
        }

        Result end(AnswerSource source, AnswerCheck check, String fallback, RuntimeException error) {
            return new Result(source, check, fallback, retried, limit, evidence(), rounds, toolCalls, inputTokens,
                    outputTokens, cacheReadTokens, cacheWriteTokens, inputForLimits, callsWithoutUsage, cost, error);
        }
    }

    /**
     * @param start           the round's fixed messages ({@link ChatPrompt#start})
     * @param historyMessages how many of them are history (the summary, then each earlier question and answer), right
     *                        after the context block
     * @param question        the outbound question (its whole numbers ground counts)
     * @param names           the names the model was given besides the results: category names and aliases
     */
    public Result run(OutboundTurn turn, List<Message> start, int historyMessages, String question,
                      Collection<String> names) {
        Run run = new Run(turn, question, names, start, historyMessages);
        while (true) {
            Integer estimate = beforeRound(run);
            if (estimate == null) {
                return fallback(run);
            }
            ChatModelClient.Reply reply;
            try {
                reply = client.call(List.copyOf(run.messages), limits.maxOutputTokens(), run.toolResultsInPrompt(),
                        run.categories);
            } catch (RuntimeException e) {
                return run.end(AnswerSource.ERROR, run.lastCheck, null, e);
            }
            run.rounds++;
            account(run, reply, estimate);

            if (!reply.toolCalls().isEmpty()) {
                runTools(run, reply);
                if (run.rounds >= limits.maxRounds()) {
                    run.hit(LimitHit.ROUNDS);
                    return fallback(run);
                }
                continue;
            }

            if (reply.text().isBlank()) {
                return fallback(run);
            }
            AnswerCheck check = null;
            if (!reply.cut()) {
                check = grounding.check(reply.text(), run.evidence());
                run.lastCheck = check;
                if (check.grounded()) {
                    return run.end(AnswerSource.MODEL, check, null, null);
                }
            }
            if (run.retried || run.limit != null) {
                return fallback(run);
            }
            if (run.rounds >= limits.maxRounds()) {
                run.hit(LimitHit.ROUNDS);
                return fallback(run);
            }
            run.retried = true;
            run.messages.add(reply.message());
            run.messages.add(new UserMessage(check == null ? CUT_ANSWER_RETRY : grounding.retryMessage(check)));
        }
    }

    /** The round's estimated input tokens, or null when a limit stops the turn (recorded on the run). */
    private Integer beforeRound(Run run) {
        if (run.rounds >= limits.maxRounds()) {
            run.hit(LimitHit.ROUNDS);
            return null;
        }
        if (clock.millis() - run.started >= limits.maxTurnTime().toMillis()) {
            run.hit(LimitHit.TIME);
            return null;
        }
        int estimate = TokenEstimate.of(run.messages, run.definitionChars);
        while (estimate > limits.maxInputTokensPerCall() && (cutOldestHistory(run) || cutOldestResult(run))) {
            estimate = TokenEstimate.of(run.messages, run.definitionChars);
        }
        if (estimate > limits.maxInputTokensPerCall()
                || run.inputForLimits + estimate > limits.maxInputTokensPerTurn()) {
            run.hit(LimitHit.TOKENS);
            return null;
        }
        if (run.price.isPresent() && !roundFits(run.cost, roundWorstCase(run.price.get(), estimate, limits,
                client.promptCaching() == ChatModelClient.PromptCaching.ON), limits)) {
            run.hit(LimitHit.COST);
            return null;
        }
        return estimate;
    }

    /**
     * The most one round can cost before it is sent: its estimated input and the output cap
     * ({@link AiPrices.Price#worstCase}). Shared with {@link ChatStartupCheck}, which asks it about the largest round
     * the token limit lets through, so startup never passes a setup this loop would refuse.
     */
    static BigDecimal roundWorstCase(AiPrices.Price price, int estimatedInputTokens, ChatProperties limits,
                                     boolean caching) {
        return price.worstCase(estimatedInputTokens, limits.maxOutputTokens(), caching);
    }

    /** Whether a round whose worst case is {@code worstCase} may start after {@code spent}; always with the limit off. */
    static boolean roundFits(BigDecimal spent, BigDecimal worstCase, ChatProperties limits) {
        return limits.maxTurnCost().signum() == 0 || spent.add(worstCase).compareTo(limits.maxTurnCost()) <= 0;
    }

    /**
     * Usage of one call; a call without usage counts at its input estimate and the output cap (§8), at the dearer of the
     * input and the cache-write price when it asked for caching. Cached-prefix tokens count against the input caps like
     * any other input token, and cost their own prices (step 8c).
     */
    private void account(Run run, ChatModelClient.Reply reply, int estimate) {
        if (reply.usageReported()) {
            long input = reply.inputTokens();
            long output = reply.outputTokens();
            run.inputTokens = (run.inputTokens == null ? 0 : run.inputTokens) + reply.inputTokens();
            run.outputTokens = (run.outputTokens == null ? 0 : run.outputTokens) + reply.outputTokens();
            run.cacheReadTokens += reply.cacheReadTokens();
            run.cacheWriteTokens += reply.cacheWriteTokens();
            run.inputForLimits += input + reply.cacheReadTokens() + reply.cacheWriteTokens();
            run.price.ifPresent(p -> run.cost = run.cost.add(p.cost(input, output, reply.cacheReadTokens(),
                    reply.cacheWriteTokens())));
        } else {
            run.callsWithoutUsage++;
            run.inputForLimits += estimate;
            run.price.ifPresent(p -> run.cost = run.cost.add(p.worstCase(estimate, limits.maxOutputTokens(),
                    reply.cached())));
        }
    }

    /** Runs the reply's tool calls in order (or skips them past a limit) and appends both messages. */
    private void runTools(Run run, ChatModelClient.Reply reply) {
        boolean lastRound = run.rounds >= limits.maxRounds();
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall modelCall : reply.toolCalls()) {
            ToolCall call = new ToolCall(modelCall.name(), modelCall.arguments());
            ToolOutcome outcome;
            if (lastRound) {
                outcome = executor.skipped(call, run.turn);
            } else if (run.toolCalls >= limits.maxToolCalls()) {
                run.hit(LimitHit.TOOL_CALLS);
                outcome = executor.skipped(call, run.turn);
            } else {
                run.toolCalls++;
                outcome = executor.execute(call, run.turn);
            }
            run.results.add(new TurnEvidence.Result(call, outcome));
            responses.add(new ToolResponseMessage.ToolResponse(modelCall.id(), modelCall.name(), outcome.json()));
        }
        run.messages.add(reply.message());
        run.messages.add(ToolResponseMessage.builder().responses(responses).build());
    }

    /**
     * Removes the oldest history message (the summary, else the oldest earlier question with its answer); false when no
     * history is left. History sits right after the system prompt and the context block ({@link ChatPrompt#start}).
     */
    private boolean cutOldestHistory(Run run) {
        if (run.historyMessages == 0) {
            return false;
        }
        int removed = run.messages.get(ChatPrompt.HISTORY_START) instanceof UserMessage ? 2 : 1;
        for (int i = 0; i < removed; i++) {
            run.messages.remove(ChatPrompt.HISTORY_START);
        }
        run.historyMessages -= removed;
        return true;
    }

    /** Replaces the oldest tool result not yet cut with the fixed cut result; false when none is left. */
    private boolean cutOldestResult(Run run) {
        if (run.cutResult == null) {
            run.cutResult = executor.cutResult(run.turn);
        }
        for (int i = 0; i < run.messages.size(); i++) {
            if (!(run.messages.get(i) instanceof ToolResponseMessage tools)) {
                continue;
            }
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(tools.getResponses());
            for (int j = 0; j < responses.size(); j++) {
                ToolResponseMessage.ToolResponse response = responses.get(j);
                if (!response.responseData().equals(run.cutResult)) {
                    responses.set(j, new ToolResponseMessage.ToolResponse(response.id(), response.name(),
                            run.cutResult));
                    run.messages.set(i, ToolResponseMessage.builder().responses(responses)
                            .metadata(tools.getMetadata()).build());
                    return true;
                }
            }
        }
        return false;
    }

    private Result fallback(Run run) {
        TurnEvidence evidence = run.evidence();
        return run.end(AnswerSource.FALLBACK, run.lastCheck, FallbackAnswer.render(evidence, run.limit), null);
    }
}
