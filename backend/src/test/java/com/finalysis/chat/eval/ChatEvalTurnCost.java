package com.finalysis.chat.eval;

import com.finalysis.ai.AiPrices;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * {@code -Deval.maxTurnCost=sized} (docs/plans/phase-9.md §2.2): a turn cost limit for the model under evaluation that
 * allows the same number of worst-case rounds as the app's own limit allows its default chat model, so models of
 * different prices are compared under the same limit in rounds, not in dollars.
 *
 * <p>A round's worst case is the app's ({@link AiPrices.Price#worstCase}): the largest input one call may send
 * ({@code max-input-tokens-per-call}) and the whole output cap ({@code max-output-tokens}), the input at the
 * cache-write price when the model caches. The limit is that worst case times the reference ratio (the app's
 * {@code max-turn-cost} over the reference model's worst case, read from the same file: 0.15 / 0.07425 for Sonnet
 * 4.6 at step 9a-4's prices), rounded up to the cent. Sonnet 4.6 sizes to 0.15 and Haiku 4.5 to 0.05, the two settings the app documents. Test code only; the app's limits
 * are unchanged.
 */
final class ChatEvalTurnCost {

    /** The value of {@code -Deval.maxTurnCost} that asks for this sizing. */
    static final String SIZED = "sized";
    /** The app's default chat model, whose limit the others are sized against. */
    static final String REFERENCE_MODEL = "us.anthropic.claude-sonnet-4-6";
    /** The app's {@code finalysis.chat.max-turn-cost} for the reference model (application.yml). */
    static final BigDecimal REFERENCE_LIMIT = new BigDecimal("0.15");
    /** The app's {@code finalysis.chat.max-input-tokens-per-call} default. */
    static final int INPUT_TOKENS_PER_CALL = 12_000;
    /** The app's {@code finalysis.chat.max-output-tokens} default. */
    static final int OUTPUT_TOKENS_PER_CALL = 1_500;

    private ChatEvalTurnCost() {
    }

    /**
     * The sized limit for a model the price file prices.
     *
     * @throws ChatEvalGuards.EvalRefusedException if the model or the reference model has no entered chat price
     */
    static BigDecimal sized(ChatEvalPrices prices, String model) {
        BigDecimal reference = worstRound(price(prices, REFERENCE_MODEL));
        return worstRound(price(prices, model)).multiply(REFERENCE_LIMIT).divide(reference, 2, RoundingMode.CEILING);
    }

    /** The most one round can cost: the app's own formula, with the cache-write price only for a model that caches. */
    static BigDecimal worstRound(ChatEvalPrices.Price price) {
        AiPrices.Price app = new AiPrices.Price(price.inputPerMillionUsd(), price.outputPerMillionUsd(),
                price.cacheReadPerMillionUsd(), price.cacheWritePerMillionUsd());
        return app.worstCase(INPUT_TOKENS_PER_CALL, OUTPUT_TOKENS_PER_CALL, app.cachePriced());
    }

    private static ChatEvalPrices.Price price(ChatEvalPrices prices, String model) {
        return prices.chat(model).orElseThrow(() -> new ChatEvalGuards.EvalRefusedException(
                "-D" + ChatEvalGuards.MAX_TURN_COST_PROPERTY + "=" + SIZED + " needs an entered chat price for " + model
                        + " in " + ChatEvalPrices.RESOURCE));
    }
}
