package com.finalysis.categorize;

import com.finalysis.ai.ChatSearchProperties;
import com.finalysis.ai.SearchDocuments;
import com.finalysis.ai.outbound.OutboundPayloadGuard;
import com.finalysis.ai.outbound.RedactionContext;
import com.finalysis.ai.outbound.RedactionContextQuery;
import com.finalysis.categorize.MerchantEmbeddingStage.EmbeddingRunResult;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the chat search index complete (CHT-02; docs/plans/phase-6.md §4.2, step 8b): the only writer of V23's
 * {@code search_embedding}. It embeds, on {@link MerchantEmbeddingStage}'s path (same hash, same embedder, same AI-06
 * line per call), every document that has no vector of the current model for that text:
 * <ol>
 *   <li>each category name ({@link SearchDocuments#category}), when the guard allows it as a category: a search can
 *       then match a category by meaning and offer its merchants;</li>
 *   <li>each distinct merchant whose outbound text may leave (the same
 *       {@link CategorizationPromptBuilder#outboundMerchant} as categorization: redacted, guard-checked, never a
 *       person-to-person payment), as that text plus its category ({@link SearchDocuments#merchant}): the category of
 *       most of its rows, ties to the newest row's, as chat's tools show it (decision 28). The bare brand ("SHELL")
 *       says little; with its category ("SHELL OIL (Transportation)") it sits nearer "gas".</li>
 * </ol>
 * Categories first, as there are few. At most {@code finalysis.chat.search.max-embeddings-per-run} calls in all; the rest
 * wait for the next refresh. Categorization's own {@code merchant_embedding} is neither read nor written here.
 *
 * <p>Runs after each AI categorization run started by the API or after an import, and on demand
 * ({@code POST /api/chat/search-index/refresh}). With AI off it returns {@code OFF} and reads nothing. Chat only reads
 * the index; it never calls this (SEC-03). Logs one line of counts (categories and merchants together), never a text.
 */
@Component
public class MerchantIndexRefresh {

    /** A merchant's category: of most of its rows, ties to the newest row's, then by name (decision 28). */
    static final String MERCHANT_CATEGORY_SQL = """
            SELECT DISTINCT ON (t.merchant) t.merchant, c.name AS category
            FROM txn t JOIN category c ON c.id = t.category_id
            WHERE t.merchant IS NOT NULL
            GROUP BY t.merchant, c.name
            ORDER BY t.merchant, count(*) DESC, max(t.txn_date) DESC, c.name""";

    private final MerchantEmbeddingStage stage;
    private final SearchEmbeddingRepository index;
    private final OutboundPayloadGuard guard;
    private final CategorizationPromptBuilder outbound;
    private final RedactionContextQuery redaction;
    private final ChatSearchProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final ReentrantLock running = new ReentrantLock();

    public MerchantIndexRefresh(MerchantEmbeddingStage stage, SearchEmbeddingRepository index,
                                OutboundPayloadGuard guard, CategorizationPromptBuilder outbound,
                                RedactionContextQuery redaction, ChatSearchProperties properties, JdbcTemplate jdbc,
                                PlatformTransactionManager transactions) {
        this.stage = stage;
        this.index = index;
        this.guard = guard;
        this.outbound = outbound;
        this.redaction = redaction;
        this.properties = properties;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /**
     * One refresh's counts with its merchants apart (step 7e: the run summary says "merchants indexed").
     *
     * @param total             categories and merchants together, as {@link #refresh()} returns them
     * @param merchantsEmbedded merchant documents embedded in this refresh
     */
    public record Refresh(EmbeddingRunResult total, int merchantsEmbedded) {
    }

    /** Embeds what is missing, merchants in name order; one refresh at a time, no transaction held across calls. */
    public EmbeddingRunResult refresh() {
        return refreshCounted().total();
    }

    /** {@link #refresh()}, with the merchants embedded counted apart from the categories. */
    public Refresh refreshCounted() {
        if (!stage.enabled()) {
            return new Refresh(EmbeddingRunResult.off(), 0);
        }
        running.lock();
        try {
            Map<String, String> categories = readOnly.execute(status -> categoryDocuments());
            Map<String, String> merchants = readOnly.execute(status -> merchantDocuments());
            int cap = properties.maxEmbeddingsPerRun();
            EmbeddingRunResult result = stage.embedInto(categories, cap, store(SearchDocuments.Kind.CATEGORY));
            EmbeddingRunResult merchantRun;
            if (result.status() == AiCategorizer.Status.STOPPED_ON_ERROR) {
                merchantRun = new EmbeddingRunResult(AiCategorizer.Status.STOPPED_ON_ERROR, merchants.size(),
                        0, 0, 0, 0, 0, merchants.size());
            } else {
                merchantRun = stage.embedInto(merchants, Math.max(0, cap - result.calls()),
                        store(SearchDocuments.Kind.MERCHANT));
            }
            result = result.plus(merchantRun);
            MerchantEmbeddingStage.log("Search index refresh", result);
            return new Refresh(result, merchantRun.embedded());
        } finally {
            running.unlock();
        }
    }

    private MerchantEmbeddingStage.Store store(SearchDocuments.Kind kind) {
        return new MerchantEmbeddingStage.Store() {
            @Override
            public Map<String, String> inputHashes(String model) {
                return index.inputHashes(kind, model);
            }

            @Override
            public void upsert(String key, String model, String inputSha256, float[] vector) {
                index.upsert(kind, key, model, inputSha256, vector);
            }
        };
    }

    /** Every category name the guard allows → its document. */
    private Map<String, String> categoryDocuments() {
        Map<String, String> documents = new LinkedHashMap<>();
        jdbc.query("SELECT name FROM category ORDER BY name", rs -> {
            String name = rs.getString("name");
            if (guard.allowsCategory(name)) {
                documents.put(name, SearchDocuments.category(name));
            }
        });
        return documents;
    }

    /** Every distinct merchant that may be embedded → its outbound text and category. */
    private Map<String, String> merchantDocuments() {
        RedactionContext context = redaction.load();
        Map<String, String> categories = new HashMap<>();
        jdbc.query(MERCHANT_CATEGORY_SQL, rs -> {
            String category = rs.getString("category");
            if (guard.allowsCategory(category)) {
                categories.put(rs.getString("merchant"), category);
            }
        });
        Map<String, String> documents = new LinkedHashMap<>();
        jdbc.query("""
                SELECT merchant, max(counterparty) AS counterparty
                FROM txn
                WHERE merchant IS NOT NULL
                GROUP BY merchant
                ORDER BY merchant""", rs -> {
            String merchant = rs.getString("merchant");
            String text = outbound.outboundMerchant(merchant, rs.getString("counterparty"), context);
            if (text != null) {
                documents.put(merchant, SearchDocuments.merchant(text, categories.get(merchant)));
            }
        });
        return documents;
    }
}
