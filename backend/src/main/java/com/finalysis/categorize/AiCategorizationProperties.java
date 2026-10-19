package com.finalysis.categorize;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Stage 5 settings ({@code finalysis.ai.categorization.*}, docs/plans/phase-4.md §4.5). They matter only when AI is
 * on; the defaults are argued in the step 7 notes, and {@code minConfidence} in the step 9c notes.
 *
 * @param batchSize      items (distinct merchants and directions) per model call
 * @param maxItemsPerRun items sent per run, all batches together; the rest wait for the next run (cost guard)
 * @param minConfidence  answers below this confidence are not applied; their txns go to the review queue
 * @param runAfterImport run stages 4 and 5 in the background after each import. Off by default in every profile:
 *                       the AI then runs only on demand ({@code POST /api/categorization/run})
 */
@ConfigurationProperties("finalysis.ai.categorization")
public record AiCategorizationProperties(@DefaultValue("25") int batchSize, @DefaultValue("500") int maxItemsPerRun,
                                         @DefaultValue("0.80") BigDecimal minConfidence,
                                         @DefaultValue("false") boolean runAfterImport) {

    /** The prompt's keys are {@code i1}… and 25 answers fit well inside the 2,000 output tokens; 50 still would. */
    static final int MAX_BATCH_SIZE = 50;

    public AiCategorizationProperties {
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
    }
}
