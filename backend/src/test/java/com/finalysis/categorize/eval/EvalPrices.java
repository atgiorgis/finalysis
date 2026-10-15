package com.finalysis.categorize.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Model prices for the evaluation's cost estimate, read from {@code eval/model-prices.json} (test resources): prices
 * live in configuration, not code. A model the file doesn't list has no estimate ({@code null}), never a guess.
 *
 * @param source where the prices come from, and when they were checked; recorded with the results
 */
record EvalPrices(String source, Map<String, Price> models) {

    static final String RESOURCE = "eval/model-prices.json";
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /** US dollars per million tokens. */
    record Price(BigDecimal inputPerMillionUsd, BigDecimal outputPerMillionUsd) {
    }

    EvalPrices {
        models = Map.copyOf(models);
    }

    static EvalPrices load() {
        try (InputStream in = EvalPrices.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the test classpath");
            }
            return JsonMapper.builder().build().readValue(in, EvalPrices.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Chat and embedding cost in US dollars at six decimals, or null when either model is unpriced. Calls without
     * usage add nothing, so with any of them it is a lower bound (the report says so).
     */
    BigDecimal cost(String chatModel, long inputTokens, long outputTokens, String embeddingModel,
                    long embeddingTokens) {
        Price chat = models.get(chatModel);
        Price embedding = embeddingTokens == 0
                ? new Price(BigDecimal.ZERO, BigDecimal.ZERO) : models.get(embeddingModel);
        if ((chat == null && inputTokens + outputTokens > 0) || embedding == null) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        if (chat != null) {
            total = total.add(chat.inputPerMillionUsd().multiply(BigDecimal.valueOf(inputTokens)))
                    .add(chat.outputPerMillionUsd().multiply(BigDecimal.valueOf(outputTokens)));
        }
        total = total.add(embedding.inputPerMillionUsd().multiply(BigDecimal.valueOf(embeddingTokens)));
        return total.divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
