package com.finalysis.chat;

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
     * @param inputTokens      reported input tokens outside a cached prefix, summed; null when no call reported usage
     * @param cacheReadTokens  the fixed prefix's tokens read from the provider's cache (step 8c); null as inputTokens
     * @param cacheWriteTokens the fixed prefix's tokens written to the cache; null as inputTokens
     * @param estimatedCost    dollars, six decimals, each kind of token at its own price; null when the model has no
     *                         price
     */
    public record Usage(String model, int rounds, Integer inputTokens, Integer outputTokens, Integer cacheReadTokens,
                        Integer cacheWriteTokens, int callsWithoutUsage, String estimatedCost) {
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
