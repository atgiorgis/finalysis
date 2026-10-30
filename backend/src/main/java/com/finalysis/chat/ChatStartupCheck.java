package com.finalysis.chat;

import org.springframework.beans.factory.ObjectProvider;
import com.finalysis.ai.PromptCache;
import com.finalysis.ai.ChatRoleProperties;
import com.finalysis.ai.AiPrices;
import com.finalysis.ai.AiProperties;
import com.finalysis.ai.OllamaProperties;
import java.math.BigDecimal;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Chat's cost limits fail closed (docs/plans/phase-6.md §8): with AI on and a cost limit set, the {@code main} role's
 * model must have a price, or startup fails. Setting both {@code finalysis.chat.max-turn-cost} and
 * {@code max-daily-cost} to 0 turns the limits off and lets an unpriced model run. The limits themselves are checked
 * when {@link ChatProperties} binds.
 *
 * <p>Step 8d: the largest round the token limit lets through must fit in {@code max-turn-cost}, priced by the loop's
 * own check ({@link TurnLoop#roundWorstCase}, {@link TurnLoop#roundFits}); otherwise every turn would stop before its
 * first call, so startup fails instead, naming the model, both amounts and the setting. This runs in the app, the
 * chat evaluation and the smoke tests alike, since each starts this context before any model call.
 *
 * <p>Phase 9 (docs/plans/phase-9.md §4.2): prices and cost limits apply on Cloud only, so this checks Cloud's main
 * model whenever Cloud is available, whichever path the vault selects later; the Ollama paths have no cost limit.
 *
 * <p>Step 9c-4 (AI-05): on the Ollama paths a call's input budget and output cap must fit in the context window asked
 * for ({@code finalysis.ai.ollama.num-ctx}), or Ollama would cut the prompt; startup refuses a smaller one, naming
 * both limits. Checked whatever path the vault selects, since the path changes without a restart.
 */
@Component
class ChatStartupCheck implements SmartInitializingSingleton {

    private final AiProperties ai;
    private final AiPrices prices;
    private final ChatProperties limits;
    private final ChatRoleProperties role;
    private final ObjectProvider<PromptCache> promptCache;
    private final OllamaProperties ollama;

    ChatStartupCheck(AiProperties ai, AiPrices prices, ChatProperties limits, ChatRoleProperties role,
                     ObjectProvider<PromptCache> promptCache, OllamaProperties ollama) {
        this.ai = ai;
        this.ollama = ollama;
        this.prices = prices;
        this.limits = limits;
        this.role = role;
        this.promptCache = promptCache;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // Cloud's rounds are priced as caching when chat would ask Cloud's provider to cache (ChatModelClient)
        boolean caching = ai.cloudAvailable() && role.promptCache() && promptCache.getIfAvailable() != null
                && prices.of(ai.roles().main()).filter(AiPrices.Price::cachePriced).isPresent();
        verify(ai, prices, limits, caching);
        verifyContext(ollama.numCtx(), limits);
    }

    /** The Ollama paths' context window holds one call's input budget plus its output cap. */
    static void verifyContext(int numCtx, ChatProperties limits) {
        int needed = limits.maxInputTokensPerCall() + limits.maxOutputTokens();
        if (numCtx < needed) {
            throw new IllegalStateException("finalysis.ai.ollama.num-ctx " + numCtx + " is smaller than "
                    + "finalysis.chat.max-input-tokens-per-call " + limits.maxInputTokensPerCall()
                    + " plus finalysis.chat.max-output-tokens " + limits.maxOutputTokens() + " (" + needed
                    + "), so Ollama would cut the prompt; raise num-ctx to at least " + needed
                    + ", or lower those limits");
        }
    }

    /** @param caching whether the loop prices its rounds as asking to cache (input at the cache-write price) */
    static void verify(AiProperties ai, AiPrices prices, ChatProperties limits, boolean caching) {
        if (!ai.cloudAvailable()) {
            return;
        }
        String model = ai.roles().main();
        if (limits.costLimited() && prices.of(model).isEmpty()) {
            throw new IllegalStateException("finalysis.ai.prices has no price for the main model "
                    + model + ", so chat's cost limits can't be estimated; add its price, or set "
                    + "finalysis.chat.max-turn-cost and finalysis.chat.max-daily-cost to 0 to turn them off");
        }
        prices.of(model).ifPresent(price -> {
            BigDecimal round = TurnLoop.roundWorstCase(price, limits.maxInputTokensPerCall(), limits, caching);
            if (!TurnLoop.roundFits(BigDecimal.ZERO, round, limits)) {
                throw new IllegalStateException("The chat model " + model + " can cost up to $"
                        + round.stripTrailingZeros().toPlainString() + " for one round ("
                        + limits.maxInputTokensPerCall() + " input tokens"
                        + (caching ? " at the cache-write price" : "") + " and " + limits.maxOutputTokens()
                        + " output tokens), more than finalysis.chat.max-turn-cost $"
                        + limits.maxTurnCost().stripTrailingZeros().toPlainString()
                        + ", so every turn would stop before its first call; raise finalysis.chat.max-turn-cost to "
                        + "at least " + round.stripTrailingZeros().toPlainString() + ", or choose a cheaper model");
            }
        });
    }
}
