package com.finalysis.categorize;

import com.finalysis.categorize.MerchantEmbeddingStage.EmbeddingRunResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/chat/search-index/refresh} (docs/plans/phase-6.md §4.2, §10.2): fills chat's merchant search index
 * now and returns the counts; {@code status: OFF} with AI off, with no call. Under chat's path because that is who
 * searches the index, but here because filling it is categorization's embedding path, and chat may not depend on it
 * (SEC-03: chat only reads). A user action, never a chat tool.
 */
@RestController
public class SearchIndexController {

    private final MerchantIndexRefresh refresh;

    public SearchIndexController(MerchantIndexRefresh refresh) {
        this.refresh = refresh;
    }

    @PostMapping("/api/chat/search-index/refresh")
    public EmbeddingRunResult refresh() {
        return refresh.refresh();
    }
}
