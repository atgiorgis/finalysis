package com.finalysis.categorize;

import com.finalysis.categorize.AiCategorizer.AiRunResult;
import com.finalysis.categorize.CategorizationService.RunResult;

/**
 * Body of {@code POST /api/categorization/run} (docs/plans/phase-4.md §9.2): the counts of stages 1–3, then the AI
 * stage's. Numbers and a status only.
 *
 * @param ai {@code status} {@code OFF} when AI is off or the request said {@code ai=false}
 */
public record RunResponse(int merchantsCleaned, int ruleCategorized, int ruleCleared, int memoryApplied,
                          AiRunResult ai) {

    static RunResponse of(RunResult rules, AiRunResult ai) {
        return new RunResponse(rules.merchantsCleaned(), rules.ruleCategorized(), rules.ruleCleared(),
                rules.memoryApplied(), ai);
    }
}
