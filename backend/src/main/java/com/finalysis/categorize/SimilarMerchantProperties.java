package com.finalysis.categorize;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Stage 4 and the examples it gives stage 5 ({@code finalysis.ai.examples.*}, CAT-03; docs/plans/phase-4.md §6 and
 * the step 8 notes). They matter only when AI is on.
 *
 * @param k                     examples per item at most
 * @param minSimilarity         cosine similarity an example needs at least (0 to 1); step 9 sets it from the dev set
 * @param maxEmbeddingsPerRun   embedding calls per run (cost guard); the rest wait for the next run
 * @param directAssignment      give an item its nearest example's category without a chat call when that example
 *                              is at least {@code directMinSimilarity} similar. Off: step 9 measures it as a variant
 * @param directMinSimilarity   the similarity a direct assignment needs
 */
@ConfigurationProperties("finalysis.ai.examples")
public record SimilarMerchantProperties(@DefaultValue("5") int k, @DefaultValue("0.65") double minSimilarity,
                                        @DefaultValue("500") int maxEmbeddingsPerRun,
                                        @DefaultValue("false") boolean directAssignment,
                                        @DefaultValue("0.97") double directMinSimilarity) {

    /** Examples per item at most; the prompt grows with every one. */
    static final int MAX_K = 10;

    public SimilarMerchantProperties {
        if (k < 0 || k > MAX_K) {
            throw new IllegalArgumentException("finalysis.ai.examples.k must be between 0 and " + MAX_K);
        }
        if (!(minSimilarity >= 0 && minSimilarity <= 1)) {
            throw new IllegalArgumentException("finalysis.ai.examples.min-similarity must be between 0 and 1");
        }
        if (maxEmbeddingsPerRun < 0) {
            throw new IllegalArgumentException("finalysis.ai.examples.max-embeddings-per-run must be at least 0");
        }
        if (!(directMinSimilarity >= minSimilarity && directMinSimilarity <= 1)) {
            throw new IllegalArgumentException(
                    "finalysis.ai.examples.direct-min-similarity must be between min-similarity and 1");
        }
    }
}
