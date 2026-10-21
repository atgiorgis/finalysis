package com.finalysis.chat.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The evaluations' price file ({@code eval/model-prices.json}, test resources; Phase 4's, shared), read for the chat
 * model under evaluation: prices live in configuration, keyed by the exact Bedrock id. The chat evaluation refuses a
 * model the file doesn't list, and gives the app the file's price for it ({@code finalysis.ai.prices[<id>]}), so the
 * turn cost limits and the reported cost use the same figures.
 *
 * @param source where the prices come from, and when they were checked; recorded with the results
 */
record ChatEvalPrices(String source, Map<String, Price> models) {

    static final String RESOURCE = "eval/model-prices.json";

    /** US dollars per million tokens. */
    record Price(BigDecimal inputPerMillionUsd, BigDecimal outputPerMillionUsd) {
    }

    ChatEvalPrices {
        models = Map.copyOf(models);
    }

    static ChatEvalPrices load() {
        try (InputStream in = ChatEvalPrices.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the test classpath");
            }
            return JsonMapper.builder().build().readValue(in, ChatEvalPrices.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A chat model's price; a model with no output price (an embedding model) is not one. */
    Optional<Price> chat(String modelId) {
        return Optional.ofNullable(models.get(modelId)).filter(p -> p.outputPerMillionUsd().signum() > 0);
    }
}
