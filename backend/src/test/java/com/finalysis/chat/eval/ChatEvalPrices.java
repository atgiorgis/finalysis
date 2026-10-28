package com.finalysis.chat.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.test.context.DynamicPropertyRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * The evaluations' price file ({@code eval/model-prices.json}, test resources; Phase 4's, shared), read for the chat
 * model under evaluation: prices live in configuration, keyed by the exact Bedrock id. The chat evaluation refuses a
 * model the file doesn't list, and gives the app the file's price for it ({@code finalysis.ai.prices[<id>]}), so the
 * turn cost limits and the reported cost use the same figures.
 *
 * @param source where the prices come from, and when they were checked; recorded with the results
 * @param note   what the owner noted about them (which are list prices, as of when; step 8c)
 */
record ChatEvalPrices(String source, String note, Map<String, Price> models) {

    static final String RESOURCE = "eval/model-prices.json";

    /**
     * US dollars per million tokens; the cache prices (step 8c) are null for a model chat doesn't cache for. Input and
     * output are both null for a model listed before the owner entered its prices (Phase 9 step 9a), never one alone.
     */
    record Price(BigDecimal inputPerMillionUsd, BigDecimal outputPerMillionUsd, BigDecimal cacheReadPerMillionUsd,
                 BigDecimal cacheWritePerMillionUsd) {

        Price {
            if ((inputPerMillionUsd == null) != (outputPerMillionUsd == null)) {
                throw new IllegalStateException("A price in " + RESOURCE + " has both inputPerMillionUsd and "
                        + "outputPerMillionUsd, or neither (not entered yet)");
            }
            if (inputPerMillionUsd == null && (cacheReadPerMillionUsd != null || cacheWritePerMillionUsd != null)) {
                throw new IllegalStateException("A price in " + RESOURCE + " without input and output prices has "
                        + "no cache prices either");
            }
        }

        /** Whether the owner has entered the input and output prices. */
        boolean entered() {
            return inputPerMillionUsd != null;
        }

        /** Both cache prices are listed, so chat asks the model to cache (the same rule as the app's). */
        boolean cachePriced() {
            return cacheReadPerMillionUsd != null && cacheWritePerMillionUsd != null;
        }
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

    /**
     * Gives the app the file's price for a chat model ({@code finalysis.ai.prices[<id>]}), cache prices included when
     * the file lists them, so the turn cost limits and the reported cost use the same figures.
     */
    void register(DynamicPropertyRegistry registry, String model) {
        Price price = chat(model).orElseThrow();
        String key = "finalysis.ai.prices[" + model + "].";
        registry.add(key + "input-per-million", price::inputPerMillionUsd);
        registry.add(key + "output-per-million", price::outputPerMillionUsd);
        if (price.cachePriced()) {
            registry.add(key + "cache-read-per-million", price::cacheReadPerMillionUsd);
            registry.add(key + "cache-write-per-million", price::cacheWritePerMillionUsd);
        }
    }

    /**
     * A chat model's price; a model with no output price (an embedding model) is not one, nor is a model whose prices
     * aren't entered yet ({@link #awaitingPrices}).
     */
    Optional<Price> chat(String modelId) {
        return Optional.ofNullable(models.get(modelId)).filter(Price::entered)
                .filter(p -> p.outputPerMillionUsd().signum() > 0);
    }

    /** The models with entered prices, sorted: what a refusal lists as priced. */
    List<String> priced() {
        return models.entrySet().stream().filter(e -> e.getValue().entered()).map(Map.Entry::getKey).sorted()
                .toList();
    }

    /** Whether the file lists the model but its prices are still null, waiting for the owner (step 9a). */
    boolean awaitingPrices(String modelId) {
        Price price = models.get(modelId);
        return price != null && !price.entered();
    }
}
