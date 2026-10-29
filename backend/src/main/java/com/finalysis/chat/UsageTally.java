package com.finalysis.chat;

import com.finalysis.ai.AiPath;
import com.finalysis.ai.AiPrices;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The usage of calls made outside a turn's rounds: the conversation summary and the monthly summary (§8). As in a turn,
 * a call without usage counts at its input estimate and the output cap, so a missing count can never make a call look
 * free; reported tokens alone are what the ledger shows as tokens.
 */
public final class UsageTally {

    private final Optional<AiPrices.Price> price;
    private int calls;
    private Integer inputTokens;
    private Integer outputTokens;
    private int cacheReadTokens;
    private int cacheWriteTokens;
    private int callsWithoutUsage;
    private BigDecimal cost;

    public UsageTally(Optional<AiPrices.Price> price) {
        this.price = price;
        this.cost = price.isPresent() ? BigDecimal.ZERO : null;
    }

    /** What a call of this estimate may cost at most (estimated input, full output cap); zero when unpriced. */
    public BigDecimal worstCase(int estimate, int maxOutputTokens) {
        return price.map(p -> p.cost(estimate, maxOutputTokens)).orElse(BigDecimal.ZERO);
    }

    public void add(ChatModelClient.Reply reply, int estimate, int maxOutputTokens) {
        calls++;
        if (reply.usageReported()) {
            inputTokens = (inputTokens == null ? 0 : inputTokens) + reply.inputTokens();
            outputTokens = (outputTokens == null ? 0 : outputTokens) + reply.outputTokens();
            cacheReadTokens += reply.cacheReadTokens();
            cacheWriteTokens += reply.cacheWriteTokens();
            price.ifPresent(p -> cost = cost.add(p.cost(reply.inputTokens(), reply.outputTokens(),
                    reply.cacheReadTokens(), reply.cacheWriteTokens())));
        } else {
            callsWithoutUsage++;
            price.ifPresent(p -> cost = cost.add(p.cost(estimate, maxOutputTokens)));
        }
    }

    public int calls() {
        return calls;
    }

    public Integer inputTokens() {
        return inputTokens;
    }

    public Integer outputTokens() {
        return outputTokens;
    }

    public int cacheReadTokens() {
        return cacheReadTokens;
    }

    public int cacheWriteTokens() {
        return cacheWriteTokens;
    }

    public int callsWithoutUsage() {
        return callsWithoutUsage;
    }

    /** Estimated cost so far; null when the model has no price. */
    public BigDecimal cost() {
        return cost;
    }

    /** Null when no call reported usage, as the input tokens. */
    private Integer cacheTokens(int tokens) {
        return inputTokens == null ? null : tokens;
    }

    /** The ledger row; {@code path} decides the cost stored ({@link ChatConversationStore#storedCost}). */
    public ChatConversationStore.Usage toUsage(UsageKind kind, String model, AiPath path, OffsetDateTime at) {
        return new ChatConversationStore.Usage(kind, model, path, calls, inputTokens, outputTokens,
                cacheTokens(cacheReadTokens), cacheTokens(cacheWriteTokens), callsWithoutUsage, cost, at);
    }
}
