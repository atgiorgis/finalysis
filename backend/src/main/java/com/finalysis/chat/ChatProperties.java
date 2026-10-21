package com.finalysis.chat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Chat settings ({@code finalysis.chat.*}, docs/plans/phase-6.md §8, §12.4). A bad value fails startup.
 *
 * @param redactTerms              words the user never wants sent (their own name, family names), replaced by
 *                                 {@code [NAME]} in a question; whole words, ignoring case; empty by default
 * @param maxRounds                model calls per turn: tool rounds, the answer and the grounding retry (1–20)
 * @param maxToolCalls             tool calls per turn; further calls get a {@code SKIPPED_LIMIT} result (1–50)
 * @param maxInputTokensPerCall    estimated before each call; the oldest tool results of the turn are cut to fit
 * @param maxInputTokensPerTurn    reported input tokens summed over the turn's calls
 * @param maxOutputTokens          the role's output cap per call; a cut answer is treated as ungrounded
 * @param maxTurnTime              wall time per turn; no round starts after it
 * @param maxTurnCost              estimated dollars per turn, checked before each call with that call's worst case;
 *                                 0 turns it off
 * @param maxDailyCost             estimated dollars per local day over every turn, conversation summary and monthly
 *                                 summary (the usage ledger); 0 turns it off
 * @param retentionDays            a conversation idle this many days is deleted, at startup and daily (§7.4; 1–3650)
 */
@ConfigurationProperties("finalysis.chat")
public record ChatProperties(@DefaultValue List<String> redactTerms,
                             @DefaultValue("5") int maxRounds,
                             @DefaultValue("8") int maxToolCalls,
                             @DefaultValue("12000") int maxInputTokensPerCall,
                             @DefaultValue("40000") int maxInputTokensPerTurn,
                             @DefaultValue("1500") int maxOutputTokens,
                             @DefaultValue("45s") Duration maxTurnTime,
                             @DefaultValue("0.05") BigDecimal maxTurnCost,
                             @DefaultValue("1.00") BigDecimal maxDailyCost,
                             @DefaultValue("90") int retentionDays) {

    public ChatProperties {
        redactTerms = redactTerms.stream().filter(term -> term != null && !term.isBlank()).map(String::strip).toList();
        between("max-rounds", maxRounds, 1, 20);
        between("max-tool-calls", maxToolCalls, 1, 50);
        between("max-input-tokens-per-call", maxInputTokensPerCall, 1_000, 200_000);
        between("max-input-tokens-per-turn", maxInputTokensPerTurn, maxInputTokensPerCall, 1_000_000);
        between("max-output-tokens", maxOutputTokens, 100, 8_000);
        if (maxTurnTime == null || maxTurnTime.compareTo(Duration.ofSeconds(5)) < 0
                || maxTurnTime.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("finalysis.chat.max-turn-time must be between 5s and 10m");
        }
        notNegative("max-turn-cost", maxTurnCost);
        notNegative("max-daily-cost", maxDailyCost);
        between("retention-days", retentionDays, 1, 3650);
    }

    /** Whether either cost limit is on; then the main model needs a price (§8). */
    public boolean costLimited() {
        return maxTurnCost.signum() > 0 || maxDailyCost.signum() > 0;
    }

    private static void between(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("finalysis.chat." + name + " must be between " + min + " and " + max);
        }
    }

    private static void notNegative(String name, BigDecimal value) {
        if (value == null || value.signum() < 0 || value.scale() > 6) {
            throw new IllegalArgumentException("finalysis.chat." + name
                    + " must be at least 0 dollars, with at most 6 decimals (0 turns it off)");
        }
    }
}
