package com.finalysis.chat;

import com.finalysis.ai.AiPrices;
import com.finalysis.ai.AiProperties;
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
 */
@Component
class ChatStartupCheck implements SmartInitializingSingleton {

    private final AiProperties ai;
    private final AiPrices prices;
    private final ChatProperties limits;
    private final ChatModelClient client;

    ChatStartupCheck(AiProperties ai, AiPrices prices, ChatProperties limits, ChatModelClient client) {
        this.ai = ai;
        this.prices = prices;
        this.limits = limits;
        this.client = client;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verify(ai, prices, limits, client.promptCaching() == ChatModelClient.PromptCaching.ON);
    }

    /** @param caching whether the loop prices its rounds as asking to cache (input at the cache-write price) */
    static void verify(AiProperties ai, AiPrices prices, ChatProperties limits, boolean caching) {
        if (!ai.enabled()) {
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
