package com.finalysis.categorize;

import com.finalysis.categorize.CategorizationModelClient.Result;
import com.finalysis.categorize.CategorizationPromptBuilder.CategorizationPrompt;
import java.math.BigDecimal;
import java.util.List;

/**
 * Sees what one AI run decided, item by item and call by call, in process (docs/plans/phase-4.md, step 9a notes).
 * The accuracy evaluation uses it to keep every answer's confidence, including the ones below
 * {@code min-confidence} that are never saved, and each call's examples block. The app passes {@link #NONE}.
 *
 * <p>It is called with local data (cleaned merchants, txn ids, prompts as sent). An implementation must never log
 * or send it anywhere.
 */
public interface AiRunObserver {

    /** Observes nothing: what every run outside the evaluation uses. */
    AiRunObserver NONE = new AiRunObserver() {
    };

    /** What happened to one item (a merchant and direction). */
    enum Outcome {
        /** Answered at or above {@code min-confidence}; its txns were given the category. */
        APPLIED,
        /** Answered below {@code min-confidence}; its txns were left for review. */
        LOW_CONFIDENCE,
        /** Rejected alone, or in a batch answered invalidly twice; its txns were left for review. */
        INVALID,
        /** Given its nearest example's category without a call (direct assignment). */
        DIRECT,
        /** Never sent: a person-to-person payment. */
        PERSON_TO_PERSON,
        /** Never sent: redaction left nothing, or the guard refused it. */
        REDACTION_BLOCKED
    }

    /**
     * One item's outcome.
     *
     * @param direction  -1 money out, 1 money in
     * @param category   the model's category (APPLIED, LOW_CONFIDENCE) or the example's (DIRECT); null otherwise
     * @param confidence the model's confidence (APPLIED, LOW_CONFIDENCE); null otherwise
     * @param similarity the nearest example's similarity (DIRECT); null otherwise
     */
    record ItemDecision(String merchant, int direction, List<Long> txnIds, Outcome outcome, String category,
                        BigDecimal confidence, Double similarity) {

        public ItemDecision {
            txnIds = List.copyOf(txnIds);
        }
    }

    /**
     * One batch, after the model answered (retries included).
     *
     * @param attempts every call made for this prompt, in order
     */
    default void batchAnswered(CategorizationPrompt prompt, List<Result> attempts) {
    }

    default void itemDecided(ItemDecision decision) {
    }
}
