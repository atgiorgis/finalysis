package com.finalysis.categorize.eval;

import com.finalysis.categorize.AiRunObserver;
import com.finalysis.categorize.CategorizationModelClient.Result;
import com.finalysis.categorize.CategorizationPromptBuilder;
import com.finalysis.categorize.CategorizationPromptBuilder.CategorizationPrompt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps what one AI run decided (docs/plans/phase-4.md, step 9a notes): every item's outcome, with the confidence of
 * answers below the threshold that the database never stores, and each batch's size, examples and calls. In memory
 * only; nothing here is logged.
 */
class EvalRecorder implements AiRunObserver {

    /**
     * One batch as sent.
     *
     * @param examplesBlockChars the {@code <examples>} block, tags included
     * @param attempts           every call for this batch: its usage is per call
     */
    record Batch(int items, int examples, int systemChars, int userChars, int examplesBlockChars,
                 List<Result> attempts) {
    }

    private final List<ItemDecision> decisions = new ArrayList<>();
    private final Map<Long, ItemDecision> byTxn = new HashMap<>();
    private final List<Batch> batches = new ArrayList<>();

    @Override
    public void batchAnswered(CategorizationPrompt prompt, List<Result> attempts) {
        String user = prompt.user();
        int examplesEnd = user.indexOf(CategorizationPromptBuilder.EXAMPLES_CLOSE_TAG);
        int examplesBlockChars = examplesEnd < 0 ? 0
                : examplesEnd + CategorizationPromptBuilder.EXAMPLES_CLOSE_TAG.length();
        batches.add(new Batch(prompt.request().items().size(), prompt.examples().examples().size(),
                prompt.system().length(), user.length(), examplesBlockChars, List.copyOf(attempts)));
    }

    @Override
    public void itemDecided(ItemDecision decision) {
        decisions.add(decision);
        decision.txnIds().forEach(id -> byTxn.put(id, decision));
    }

    List<Batch> batches() {
        return List.copyOf(batches);
    }

    List<ItemDecision> decisions() {
        return List.copyOf(decisions);
    }

    /** The decision that covered this txn, or null (rules, or never reached). */
    ItemDecision decision(long txnId) {
        return byTxn.get(txnId);
    }
}
