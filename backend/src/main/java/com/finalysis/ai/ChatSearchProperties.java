package com.finalysis.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Meaning-based merchant search for chat ({@code finalysis.chat.search.*}, docs/plans/phase-6.md §4). Here, not in
 * {@code chat}, because both sides of the index use it: {@code categorize} fills it ({@code MerchantIndexRefresh}) and
 * {@code chat} reads it, and neither may depend on the other.
 *
 * @param minSimilarity       the cosine similarity a merchant needs to be a search candidate (0 to 1); step 8 sets it
 *                            from the dev set
 * @param maxEmbeddingsPerRun embedding calls per index refresh at most (cost guard); the rest wait for the next one
 */
@ConfigurationProperties("finalysis.chat.search")
public record ChatSearchProperties(@DefaultValue("0.40") double minSimilarity,
                                   @DefaultValue("500") int maxEmbeddingsPerRun) {

    public ChatSearchProperties {
        if (!(minSimilarity >= 0 && minSimilarity <= 1)) {
            throw new IllegalArgumentException("finalysis.chat.search.min-similarity must be between 0 and 1");
        }
        if (maxEmbeddingsPerRun < 0) {
            throw new IllegalArgumentException("finalysis.chat.search.max-embeddings-per-run must be at least 0");
        }
    }
}
