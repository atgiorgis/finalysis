package com.finalysis.categorize;

import com.finalysis.ai.AiPath;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Stage 5 settings ({@code finalysis.ai.categorization.*}, docs/plans/phase-4.md §4.5). They matter only when AI is
 * on; the defaults are argued in the step 7 notes, and {@code minConfidence} in the step 9c notes.
 *
 * @param batchSize      items (distinct merchants and directions) per model call
 * @param maxItemsPerRun items sent per run, all batches together; the rest wait for the next run (cost guard)
 * @param minConfidence       answers below this confidence are not applied; their txns go to the review queue
 * @param runAfterImport      run stages 4 and 5 in the background after each import. Off by default in every
 *                            profile: the AI then runs only on demand ({@code POST /api/categorization/run})
 * @param minConfidenceByPath the threshold on a path that has its own (docs/plans/phase-9.md, 9c-11 notes): the
 *                            Ollama model is more confident when wrong than Haiku, so its paths ask for 0.85; a path
 *                            not listed uses {@code minConfidence}
 */
@ConfigurationProperties("finalysis.ai.categorization")
public record AiCategorizationProperties(int batchSize, int maxItemsPerRun, BigDecimal minConfidence,
                                         boolean runAfterImport, Map<AiPath, BigDecimal> minConfidenceByPath) {

    @ConstructorBinding
    public AiCategorizationProperties(@DefaultValue("25") int batchSize, @DefaultValue("500") int maxItemsPerRun,
                                      @DefaultValue("0.80") BigDecimal minConfidence,
                                      @DefaultValue("false") boolean runAfterImport,
                                      @DefaultValue Map<AiPath, BigDecimal> minConfidenceByPath) {
        this.batchSize = batchSize;
        this.maxItemsPerRun = maxItemsPerRun;
        this.minConfidence = minConfidence;
        this.runAfterImport = runAfterImport;
        this.minConfidenceByPath = minConfidenceByPath == null ? Map.of() : Map.copyOf(minConfidenceByPath);
        validate();
    }

    /** One threshold on every path (the evaluation's variants, the tests). */
    public AiCategorizationProperties(int batchSize, int maxItemsPerRun, BigDecimal minConfidence,
                                      boolean runAfterImport) {
        this(batchSize, maxItemsPerRun, minConfidence, runAfterImport, Map.of());
    }

    /** The threshold on this path: its own when listed, else {@code minConfidence}. */
    public BigDecimal minConfidence(AiPath path) {
        return path == null ? minConfidence : minConfidenceByPath.getOrDefault(path, minConfidence);
    }

    /** The prompt's keys are {@code i1}… and 25 answers fit well inside the 2,000 output tokens; 50 still would. */
    static final int MAX_BATCH_SIZE = 50;

    private void validate() {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "finalysis.ai.categorization.batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (maxItemsPerRun < 1) {
            throw new IllegalArgumentException("finalysis.ai.categorization.max-items-per-run must be at least 1");
        }
        if (minConfidence == null || minConfidence.signum() < 0 || minConfidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("finalysis.ai.categorization.min-confidence must be between 0 and 1");
        }
        minConfidenceByPath.forEach((path, value) -> {
            if (path == AiPath.OFF || value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("finalysis.ai.categorization.min-confidence-by-path[" + path
                        + "] must be between 0 and 1, on CLOUD, SELF_HOSTED or LOCAL");
            }
        });
    }
}
