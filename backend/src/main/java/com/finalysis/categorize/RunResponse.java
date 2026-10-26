package com.finalysis.categorize;

import com.finalysis.categorize.AiCategorizer.AiRunResult;
import com.finalysis.categorize.CategorizationService.RunResult;
import com.finalysis.categorize.MerchantEmbeddingStage.EmbeddingRunResult;
import com.finalysis.categorize.MerchantIndexRefresh.Refresh;

/**
 * Body of {@code POST /api/categorization/run} (docs/plans/phase-4.md §9.2): the counts of stages 1–3, then the AI
 * stage's, the chat search index refresh that follows an AI run, and a summary for the UI. Numbers and a status only.
 *
 * @param ai          {@code status} {@code OFF} when AI is off or the request said {@code ai=false}
 * @param searchIndex the refresh after the AI stage (docs/plans/phase-6.md §4.2); {@code status} {@code OFF} when
 *                    the AI stage didn't run (step 7e: returned as well as logged)
 * @param summary     the counts the UI says in words, added up here so the UI adds nothing (step 7e)
 */
public record RunResponse(int merchantsCleaned, int ruleCategorized, int ruleCleared, int memoryApplied,
                          AiRunResult ai, EmbeddingRunResult searchIndex, Summary summary) {

    /**
     * @param categorized      txns given a category, or a different one, in this run: by a rule, by memory or by the
     *                         AI
     * @param toReview         txns without a category after the run (the "Needs a category" count)
     * @param heldBack         merchants never sent because redaction left nothing or the guard refused them (SEC-01)
     * @param modelCalls       categorization model calls, retries included
     * @param merchantsIndexed merchants embedded for chat search by the refresh (category names not counted)
     */
    public record Summary(int categorized, long toReview, int heldBack, int modelCalls, int merchantsIndexed) {
    }

    static RunResponse of(RunResult rules, AiRunResult ai, Refresh searchIndex, long toReview) {
        Summary summary = new Summary(rules.ruleCategorized() + rules.memoryApplied() + ai.txnsCategorized(), toReview,
                ai.redactionBlocked(), ai.calls(), searchIndex.merchantsEmbedded());
        return new RunResponse(rules.merchantsCleaned(), rules.ruleCategorized(), rules.ruleCleared(),
                rules.memoryApplied(), ai, searchIndex.total(), summary);
    }
}
