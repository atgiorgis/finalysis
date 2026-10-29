package com.finalysis.ai;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Meaning-based merchant search for chat ({@code finalysis.chat.search.*}, docs/plans/phase-6.md §4). Here, not in
 * {@code chat}, because both sides of the index use it: {@code categorize} fills it ({@code MerchantIndexRefresh}) and
 * {@code chat} reads it, and neither may depend on the other.
 *
 * @param minSimilarity        the cosine similarity a merchant needs to be a search candidate (0 to 1), for an
 *                             embedding model without its own floor
 * @param minSimilarityByModel the floor per embedding model (docs/plans/phase-9.md §4.1): similarities aren't comparable
 *                             across models, so each is tuned on its own. Titan V2 keeps 0.40; nomic-embed-text is
 *                             provisional at 0.40 until the owner's dev sweep on Ollama
 * @param maxEmbeddingsPerRun  embedding calls per index refresh at most (cost guard); the rest wait for the next one
 */
@ConfigurationProperties("finalysis.chat.search")
public record ChatSearchProperties(double minSimilarity, Map<String, Double> minSimilarityByModel,
                                   int maxEmbeddingsPerRun) {

    @ConstructorBinding
    public ChatSearchProperties(@DefaultValue("0.40") double minSimilarity,
                                @DefaultValue Map<String, Double> minSimilarityByModel,
                                @DefaultValue("500") int maxEmbeddingsPerRun) {
        if (!(minSimilarity >= 0 && minSimilarity <= 1)) {
            throw new IllegalArgumentException("finalysis.chat.search.min-similarity must be between 0 and 1");
        }
        minSimilarityByModel = Map.copyOf(minSimilarityByModel == null ? Map.of() : minSimilarityByModel);
        minSimilarityByModel.forEach((model, floor) -> {
            if (floor == null || !(floor >= 0 && floor <= 1)) {
                throw new IllegalArgumentException("finalysis.chat.search.min-similarity-by-model[" + model
                        + "] must be between 0 and 1");
            }
        });
        if (maxEmbeddingsPerRun < 0) {
            throw new IllegalArgumentException("finalysis.chat.search.max-embeddings-per-run must be at least 0");
        }
        this.minSimilarity = minSimilarity;
        this.minSimilarityByModel = minSimilarityByModel;
        this.maxEmbeddingsPerRun = maxEmbeddingsPerRun;
    }

    /** The floor before Phase 9's per-model floors (tests and callers that name no model). */
    public ChatSearchProperties(double minSimilarity, int maxEmbeddingsPerRun) {
        this(minSimilarity, Map.of(), maxEmbeddingsPerRun);
    }

    /** The floor for this embedding model: its own, or {@link #minSimilarity()}. */
    public double minSimilarity(String model) {
        return model == null ? minSimilarity : minSimilarityByModel.getOrDefault(model, minSimilarity);
    }
}
