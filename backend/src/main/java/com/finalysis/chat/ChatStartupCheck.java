package com.finalysis.chat;

import com.finalysis.ai.AiPrices;
import com.finalysis.ai.AiProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Chat's cost limits fail closed (docs/plans/phase-6.md §8): with AI on and a cost limit set, the {@code main} role's
 * model must have a price, or startup fails. Setting both {@code finalysis.chat.max-turn-cost} and
 * {@code max-daily-cost} to 0 turns the limits off and lets an unpriced model run. The limits themselves are checked
 * when {@link ChatProperties} binds.
 */
@Component
class ChatStartupCheck implements SmartInitializingSingleton {

    private final AiProperties ai;
    private final AiPrices prices;
    private final ChatProperties limits;

    ChatStartupCheck(AiProperties ai, AiPrices prices, ChatProperties limits) {
        this.ai = ai;
        this.prices = prices;
        this.limits = limits;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verify(ai, prices, limits);
    }

    static void verify(AiProperties ai, AiPrices prices, ChatProperties limits) {
        if (ai.enabled() && limits.costLimited() && prices.of(ai.roles().main()).isEmpty()) {
            throw new IllegalStateException("finalysis.ai.prices has no price for the main model "
                    + ai.roles().main() + ", so chat's cost limits can't be estimated; add its price, or set "
                    + "finalysis.chat.max-turn-cost and finalysis.chat.max-daily-cost to 0 to turn them off");
        }
    }
}
