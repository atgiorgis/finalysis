package com.finalysis.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Model prices ({@code finalysis.ai.prices.<model-id>}, docs/plans/phase-6.md §8), in US dollars per million tokens:
 * what the chat cost limits estimate from. The same values as the evaluation's price file. A model without an entry
 * has no estimate, never a guess; chat then refuses to start unless its cost limits are off.
 *
 * <p>Model ids hold dots and colons, so a key is written in brackets:
 * {@code finalysis.ai.prices."[us.anthropic.claude-haiku-4-5-20251001-v1:0]".input-per-million: 1.00}.
 *
 * <p>Prompt caching (step 8c) has two more prices: a cached-prefix read and a cache write. Chat asks for caching only
 * for a model that has both ({@link Price#cachePriced}), so a model without them runs uncached.
 */
@ConfigurationProperties("finalysis.ai")
public record AiPrices(Map<String, Price> prices) {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    /** A cache write costs at most twice the input price (the 1-hour TTL); the bound for a write with no price. */
    private static final BigDecimal MOST_A_WRITE_COSTS = BigDecimal.valueOf(2);

    /**
     * Dollars per million input and output tokens, and per million cached-prefix tokens read and written.
     *
     * @param cacheReadPerMillion  null for a model chat doesn't cache for
     * @param cacheWritePerMillion null for a model chat doesn't cache for
     */
    public record Price(BigDecimal inputPerMillion, BigDecimal outputPerMillion, BigDecimal cacheReadPerMillion,
                        BigDecimal cacheWritePerMillion) {

        @ConstructorBinding
        public Price {
            if (inputPerMillion == null || outputPerMillion == null || inputPerMillion.signum() < 0
                    || outputPerMillion.signum() < 0) {
                throw new IllegalArgumentException("a model price needs input-per-million and output-per-million, "
                        + "both at least 0");
            }
            if ((cacheReadPerMillion == null) != (cacheWritePerMillion == null)
                    || (cacheReadPerMillion != null && (cacheReadPerMillion.signum() < 0
                    || cacheWritePerMillion.signum() < 0))) {
                throw new IllegalArgumentException("cache-read-per-million and cache-write-per-million go together, "
                        + "both at least 0");
            }
        }

        /** A price without cache prices: chat never caches for the model. */
        public Price(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
            this(inputPerMillion, outputPerMillion, null, null);
        }

        /** Both cache prices are set, so chat may ask this model to cache. */
        public boolean cachePriced() {
            return cacheReadPerMillion != null;
        }

        /** The cost of a call without cache tokens, to the micro-dollar ({@code NUMERIC(10,6)}), half-even. */
        public BigDecimal cost(long inputTokens, long outputTokens) {
            return cost(inputTokens, outputTokens, 0, 0);
        }

        /**
         * The cost of a call, each kind of token at its own price, to the micro-dollar, half-even. Cache tokens a model
         * reports without cache prices (chat never asks it to cache, so this shouldn't happen) count at the most they
         * could cost: a read as input, a write at twice the input price, so they never make a call look cheaper.
         *
         * @param inputTokens input tokens outside the cached prefix (Bedrock's {@code inputTokens})
         */
        public BigDecimal cost(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
            BigDecimal read = cacheReadPerMillion != null ? cacheReadPerMillion : inputPerMillion;
            BigDecimal write = cacheWritePerMillion != null ? cacheWritePerMillion
                    : inputPerMillion.multiply(MOST_A_WRITE_COSTS);
            return inputPerMillion.multiply(BigDecimal.valueOf(inputTokens))
                    .add(outputPerMillion.multiply(BigDecimal.valueOf(outputTokens)))
                    .add(read.multiply(BigDecimal.valueOf(cacheReadTokens)))
                    .add(write.multiply(BigDecimal.valueOf(cacheWriteTokens)))
                    .divide(MILLION, 6, RoundingMode.HALF_EVEN);
        }

        /**
         * The most a call can cost before it is sent: every estimated input token at the dearer of the input and, when
         * the call asks for caching, the cache-write price, and the whole output cap.
         */
        public BigDecimal worstCase(long estimatedInputTokens, long maxOutputTokens, boolean caching) {
            BigDecimal input = caching && cacheWritePerMillion != null
                    ? inputPerMillion.max(cacheWritePerMillion) : inputPerMillion;
            return input.multiply(BigDecimal.valueOf(estimatedInputTokens))
                    .add(outputPerMillion.multiply(BigDecimal.valueOf(maxOutputTokens)))
                    .divide(MILLION, 6, RoundingMode.HALF_EVEN);
        }
    }

    public AiPrices {
        prices = prices == null ? Map.of() : Map.copyOf(prices);
    }

    public Optional<Price> of(String modelId) {
        return Optional.ofNullable(modelId == null ? null : prices.get(modelId));
    }
}
