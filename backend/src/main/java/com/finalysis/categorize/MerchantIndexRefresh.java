package com.finalysis.categorize;

import com.finalysis.ai.ChatSearchProperties;
import com.finalysis.ai.outbound.RedactionContext;
import com.finalysis.ai.outbound.RedactionContextQuery;
import com.finalysis.categorize.MerchantEmbeddingStage.EmbeddingRunResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the chat search index complete (CHT-02; docs/plans/phase-6.md §4.2): categorization embeds only the merchants it
 * needs, so many have no vector. This embeds every distinct merchant whose outbound text may leave (the same
 * {@link CategorizationPromptBuilder#outboundMerchant} as categorization: redacted, guard-checked, never a
 * person-to-person payment) and that has no vector of the current model for that text, on
 * {@link MerchantEmbeddingStage}'s path: same hash, same embedder, same AI-06 line per call. At most
 * {@code finalysis.chat.search.max-embeddings-per-run} calls; the rest wait for the next refresh.
 *
 * <p>Runs after each AI categorization run started by the API or after an import, and on demand
 * ({@code POST /api/chat/search-index/refresh}). With AI off it returns {@code OFF} and reads nothing. Chat only reads
 * the index; it never calls this (SEC-03). Logs counts only.
 */
@Component
public class MerchantIndexRefresh {

    private final MerchantEmbeddingStage stage;
    private final CategorizationPromptBuilder outbound;
    private final RedactionContextQuery redaction;
    private final ChatSearchProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final ReentrantLock running = new ReentrantLock();

    public MerchantIndexRefresh(MerchantEmbeddingStage stage, CategorizationPromptBuilder outbound,
                                RedactionContextQuery redaction, ChatSearchProperties properties, JdbcTemplate jdbc,
                                PlatformTransactionManager transactions) {
        this.stage = stage;
        this.outbound = outbound;
        this.redaction = redaction;
        this.properties = properties;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /** Embeds what is missing, merchants in name order; one refresh at a time, no transaction held across calls. */
    public EmbeddingRunResult refresh() {
        if (!stage.enabled()) {
            return EmbeddingRunResult.off();
        }
        running.lock();
        try {
            Map<String, String> textByMerchant = readOnly.execute(status -> outboundTexts());
            return stage.embedAll(textByMerchant, properties.maxEmbeddingsPerRun(), "Search index refresh");
        } finally {
            running.unlock();
        }
    }

    /** Every distinct merchant that may be embedded → its outbound text. */
    private Map<String, String> outboundTexts() {
        RedactionContext context = redaction.load();
        Map<String, String> texts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT merchant, max(counterparty) AS counterparty
                FROM txn
                WHERE merchant IS NOT NULL
                GROUP BY merchant
                ORDER BY merchant""", rs -> {
            String text = outbound.outboundMerchant(rs.getString("merchant"), rs.getString("counterparty"), context);
            if (text != null) {
                texts.put(rs.getString("merchant"), text);
            }
        });
        return texts;
    }
}
