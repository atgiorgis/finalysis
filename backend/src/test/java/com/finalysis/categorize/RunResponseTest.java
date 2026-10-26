package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.categorize.AiCategorizer.AiRunResult;
import com.finalysis.categorize.CategorizationService.RunResult;
import com.finalysis.categorize.MerchantEmbeddingStage.EmbeddingRunResult;
import com.finalysis.categorize.MerchantIndexRefresh.Refresh;
import org.junit.jupiter.api.Test;

/** The run summary (Phase 7 step 7e): each count from its stage, added up in Java so the UI adds nothing. */
class RunResponseTest {

    @Test
    void theSummaryAddsTheStagesAndTakesHeldBackCallsAndMerchantsFromThem() {
        RunResult rules = new RunResult(5, 60, 2, 33);
        AiRunResult ai = new AiRunResult(AiCategorizer.Status.DONE, 40, 38, 4, 30, 2, 1, 1, 4, 0, 0, 1200, 300, 0, 0,
                10, 0, EmbeddingRunResult.off());
        EmbeddingRunResult index = new EmbeddingRunResult(AiCategorizer.Status.DONE, 140, 0, 140, 140, 0, 900, 0);

        RunResponse response = RunResponse.of(rules, ai, new Refresh(index, 126), 34);

        assertThat(response.summary()).isEqualTo(new RunResponse.Summary(97, 34, 1, 4, 126));
        assertThat(response.searchIndex()).isEqualTo(index);
    }
}
