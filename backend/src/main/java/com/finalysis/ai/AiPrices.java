package com.finalysis.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Model prices ({@code finalysis.ai.prices.<model-id>}, docs/plans/phase-6.md §8), in US dollars per million tokens:
 * what the chat cost limits estimate from. The same values as the evaluation's price file. A model without an entry
 * has no estimate, never a guess; chat then refuses to start unless its cost limits are off.
 *
 * <p>Model ids hold dots and colons, so a key is written in brackets:
 * {@code finalysis.ai.prices."[us.anthropic.claude-haiku-4-5-20251001-v1:0]".input-per-million: 1.00}.
 */
@ConfigurationProperties("finalysis.ai")
public record AiPrices(Map<String, Price> prices) {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /** Dollars per million input and output tokens. */
    public record Price(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {

        public Price {
            if (inputPerMillion == null || outputPerMillion == null || inputPerMillion.signum() < 0
                    || outputPerMillion.signum() < 0) {
                throw new IllegalArgumentException("a model price needs input-per-million and output-per-million, "
                        + "both at least 0");
            }
        }

        /** The cost of a call, to the micro-dollar ({@code NUMERIC(10,6)}), half-even. */
        public BigDecimal cost(long inputTokens, long outputTokens) {
            return inputPerMillion.multiply(BigDecimal.valueOf(inputTokens))
                    .add(outputPerMillion.multiply(BigDecimal.valueOf(outputTokens)))
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
