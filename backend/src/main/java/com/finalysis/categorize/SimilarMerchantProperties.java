package com.finalysis.categorize;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Stage 4 and the examples it gives stage 5 ({@code finalysis.ai.examples.*}, CAT-03; docs/plans/phase-4.md §6 and
 * the step 8 and 9a notes). They matter only when AI is on.
 *
 * @param k                     examples per item at most
 * @param minSimilarity         cosine similarity an example needs at least (0 to 1); step 9 sets it from the dev set
 * @param maxEmbeddingsPerRun   embedding calls per run (cost guard); the rest wait for the next run
 * @param directAssignment      give an item its nearest example's category without a chat call when that example
 *                              is at least {@code directMinSimilarity} similar. Off: step 9 measures it as a variant
 * @param directMinSimilarity   the similarity a direct assignment needs
 * @param maxExamplesPerBatch   distinct examples in one call's {@code <examples>} block at most (0 to
 *                              {@value #MAX_EXAMPLES_PER_BATCH}); every item's nearest example is kept before any
 *                              item's second. The step 9 eval reports the block's share of input tokens
 */
@ConfigurationProperties("finalysis.ai.examples")
public record SimilarMerchantProperties(@DefaultValue("5") int k, @DefaultValue("0.65") double minSimilarity,
                                        @DefaultValue("500") int maxEmbeddingsPerRun,
                                        @DefaultValue("false") boolean directAssignment,
                                        @DefaultValue("0.97") double directMinSimilarity,
                                        @DefaultValue("50") int maxExamplesPerBatch) {

    /** Examples per item at most; the prompt grows with every one. */
    static final int MAX_K = 10;
    /** A full batch with {@link #MAX_K} examples per item: a larger cap would never bind. */
    static final int MAX_EXAMPLES_PER_BATCH = AiCategorizationProperties.MAX_BATCH_SIZE * MAX_K;

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
        if (maxExamplesPerBatch < 0 || maxExamplesPerBatch > MAX_EXAMPLES_PER_BATCH) {
            throw new IllegalArgumentException("finalysis.ai.examples.max-examples-per-batch must be between 0 and "
                    + MAX_EXAMPLES_PER_BATCH);
        }
    }
}
