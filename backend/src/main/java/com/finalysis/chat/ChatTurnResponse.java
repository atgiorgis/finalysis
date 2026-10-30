package com.finalysis.chat;

import com.finalysis.ai.AiPath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.finalysis.chat.citation.Citation;
import com.finalysis.chat.grounding.GroundingStatus;
import com.finalysis.chat.tool.DataNote;
import java.util.List;

/**
 * One answered turn, for the local client only (docs/plans/phase-6.md §10.2): the answer as shown, how it was
 * checked, its citations, the data notes of the months its tools read, the tool calls (counts only), usage, the limit
 * reached, and what was removed or withheld from what was sent (counts only). Never a raw tool result, the outbound
 * payloads or the text of an answer that wasn't shown.
 *
 * @param answer the answer with numbered markers ({@code [1]}); for a fallback, the Java-built list
 */
public record ChatTurnResponse(long conversationId, int turn, String answer, AnswerSource answerSource,
                               Grounding grounding, List<Citation> citations, List<DataNote> dataNotes,
                               List<ToolUse> tools, Usage usage, LimitHit limitHit, Redactions redactions) {

    public ChatTurnResponse {
        citations = List.copyOf(citations);
        dataNotes = List.copyOf(dataNotes);
        tools = List.copyOf(tools);
    }

    /**
     * @param figuresChecked figures in the shown answer, or in the last answer checked before a fallback
     * @param ungrounded     figures that matched nothing in that answer (count only)
     */
    public record Grounding(GroundingStatus status, int figuresChecked, boolean retried, int ungrounded) {
    }

    /** One tool call: its name ({@code null} for a name the model invented), status, rows sent and time. */
    public record ToolUse(String name, ToolStatus status, int rows, long ms) {
    }

    /**
     * @param path             where the turn ran (docs/plans/phase-9.md §4.2)
     * @param inputTokens      reported input tokens outside a cached prefix, summed; null when no call reported usage
     * @param cacheReadTokens  the fixed prefix's tokens read from the provider's cache (step 8c); null as inputTokens
     * @param cacheWriteTokens the fixed prefix's tokens written to the cache; null as inputTokens
     * @param estimatedCost    dollars, six decimals, each kind of token at its own price, on Cloud; {@code 0.000000} on
     *                         Local; null on Self-hosted, and when a Cloud model has no price
     * @param costNote         on Self-hosted, where the cost is time, not tokens: the server's running line for a turn
     *                         just answered ("running 42 min · ~$0.70", docs/plans/phase-9.md §4.2), or
     *                         {@value #SELF_HOSTED_COST} for a turn read back from history or when the running time
     *                         can't be read; null on the other paths
     */
    public record Usage(String model, AiPath path, int rounds, Integer inputTokens, Integer outputTokens,
                        Integer cacheReadTokens, Integer cacheWriteTokens, int callsWithoutUsage, String estimatedCost,
                        String costNote) {

        public static final String SELF_HOSTED_COST = "Self-hosted cost is the server's running time";

        /** The usage of a turn or summary on this path, its cost as stored ({@link ChatConversationStore#storedCost}). */
        public static Usage of(String model, AiPath path, int rounds, Integer inputTokens, Integer outputTokens,
                               Integer cacheReadTokens, Integer cacheWriteTokens, int callsWithoutUsage,
                               BigDecimal estimate) {
            BigDecimal cost = path == null ? estimate : ChatConversationStore.storedCost(path, estimate);
            return new Usage(model, path, rounds, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens,
                    callsWithoutUsage, cost == null ? null : cost.setScale(6, RoundingMode.HALF_EVEN).toPlainString(),
                    path == AiPath.SELF_HOSTED ? SELF_HOSTED_COST : null);
        }

        /** This usage with the Self-hosted server's running line as its cost note, when there is one. */
        public Usage withRunningLine(String line) {
            if (path != AiPath.SELF_HOSTED || line == null) {
                return this;
            }
            return new Usage(model, path, rounds, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens,
                    callsWithoutUsage, estimatedCost, line);
        }
    }

    /**
     * @param accountAliases     account references in the question replaced by an alias
     * @param identifiersRemoved emails, URLs, numbers, unknown last fours, people and redact terms removed
     * @param withheldMerchants  distinct merchant names withheld from the payloads (instruction-like or with an amount)
     * @param refusedMerchants   distinct merchant names refused (person-to-person, empty after redaction, or by the guard)
     */
    public record Redactions(int accountAliases, int identifiersRemoved, int withheldMerchants, int refusedMerchants) {
    }
}
