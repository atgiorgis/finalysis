package com.finalysis.categorize;

import com.finalysis.ai.AiProperties;
import com.finalysis.ai.TextEmbedder;
import com.finalysis.ai.TextEmbedder.Embedding;
import com.finalysis.categorize.AiCategorizer.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stage 4 of the pipeline (CAT-03, DAT-06; docs/plans/phase-4.md §1.1, §6, step 8 notes): merchant embeddings, and
 * the similar merchants they find for stage 5.
 *
 * <p><b>What is embedded.</b> The merchant's outbound text: the cleaned merchant through the {@link Redactor} and
 * {@link OutboundPayloadGuard}, by {@link CategorizationPromptBuilder#outboundMerchant}, the same method that writes
 * the chat model's items and examples. Never the raw description. A person-to-person merchant, or one the guard
 * refuses, is never embedded. The key stays the cleaned {@code txn.merchant}.
 *
 * <p><b>Which merchants, and when.</b> Only when stage 5 has items to send: first the items' merchants (they need
 * neighbours now), then merchants with a trusted (USER or RULE) category that lack a current vector (they are the
 * examples). Each merchant is embedded once per model and cached; it is embedded again only when its outbound text
 * (its SHA-256 is stored) or the model changes. At most {@code max-embeddings-per-run} calls per run; the rest wait
 * for the next run. A failed call stops this stage only: stage 5 goes on with the vectors it has, and the next run
 * embeds the rest.
 *
 * <p><b>Examples.</b> For an item's vector: the nearest merchants of the same model, the item's own merchant excluded,
 * each with the category of its newest USER or RULE txn in the item's direction, at least {@code min-similarity},
 * at most {@code k}. AI categories never make an example, so the model never learns from its own answers.
 *
 * <p>Logs counts and exception class names only, never a merchant.
 */
@Component
public class MerchantEmbeddingStage {

    private static final Logger log = LoggerFactory.getLogger(MerchantEmbeddingStage.class);

    /**
     * Neighbours the index is asked for before the label and similarity filters. pgvector's HNSW scan returns at most
     * {@code hnsw.ef_search} rows (40 by default), so asking for more gains nothing.
     */
    static final int POOL = 40;

    private final TextEmbedder embedder;
    private final MerchantEmbeddingRepository embeddings;
    private final CategorizationPromptBuilder outbound;
    private final SimilarMerchantProperties properties;
    private final JdbcTemplate jdbc;

    public MerchantEmbeddingStage(AiProperties ai, TextEmbedder embedder, MerchantEmbeddingRepository embeddings,
                                  CategorizationPromptBuilder outbound, SimilarMerchantProperties properties,
                                  JdbcTemplate jdbc) {
        if (ai.enabled() && embedder.dimensions() != MerchantEmbeddingRepository.DIMENSIONS) {
            throw new IllegalStateException("finalysis.ai.embedding.dimensions is " + embedder.dimensions()
                    + " but merchant_embedding stores vector(" + MerchantEmbeddingRepository.DIMENSIONS
                    + "); configure an embedding model with " + MerchantEmbeddingRepository.DIMENSIONS + " dimensions");
        }
        this.embedder = embedder;
        this.embeddings = embeddings;
        this.outbound = outbound;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    /**
     * A merchant stage 5 wants to send.
     *
     * @param counterparty any of its txns' person-to-person counterparty; such a merchant is never embedded
     */
    public record Need(String merchant, String counterparty) {
    }

    /**
     * Counts from one run of this stage; numbers only, so safe to log and return.
     *
     * @param status            {@code OFF} when nothing was needed or no model is configured
     * @param merchants         distinct merchants that may be embedded: the items', then the labeled ones
     * @param cached            of those, already embedded from the same text by the same model
     * @param embedded          embedded in this run
     * @param calls             embedding calls, failed ones included
     * @param callsWithoutUsage answered calls whose provider reported no token usage; their tokens aren't in the
     *                          total
     * @param inputTokens       input tokens of the calls that reported usage
     * @param remaining         merchants not embedded because of the cap or a failure
     */
    public record EmbeddingRunResult(Status status, int merchants, int cached, int embedded, int calls,
                                     int callsWithoutUsage, long inputTokens, int remaining) {

        static EmbeddingRunResult off() {
            return new EmbeddingRunResult(Status.OFF, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * What the run made: its counts, and the current vector of each item merchant that has one (an item without one
     * gets no examples).
     */
    public record Outcome(EmbeddingRunResult result, Map<String, float[]> vectors) {
    }

    /** Embeds what is missing or stale, items first; see the class comment. Makes no call when nothing is needed. */
    public Outcome embed(List<Need> items, RedactionContext context) {
        if (items.isEmpty() || !embedder.enabled()) {
            return new Outcome(EmbeddingRunResult.off(), Map.of());
        }
        String model = embedder.modelId();
        Map<String, String> textByMerchant = new LinkedHashMap<>();
        for (Need item : items) {
            String text = outbound.outboundMerchant(item.merchant(), item.counterparty(), context);
            if (text != null) {
                textByMerchant.putIfAbsent(item.merchant(), text);
            }
        }
        List<String> itemMerchants = List.copyOf(textByMerchant.keySet());
        if (itemMerchants.isEmpty()) {
            return new Outcome(EmbeddingRunResult.off(), Map.of()); // nothing will be sent, so no examples are needed
        }
        jdbc.query("""
                SELECT merchant, max(counterparty) AS counterparty
                FROM txn
                WHERE merchant IS NOT NULL
                GROUP BY merchant
                HAVING bool_or(category_source IN ('USER', 'RULE'))
                ORDER BY merchant""", rs -> {
            String merchant = rs.getString("merchant");
            if (!textByMerchant.containsKey(merchant)) {
                String text = outbound.outboundMerchant(merchant, rs.getString("counterparty"), context);
                if (text != null) {
                    textByMerchant.put(merchant, text);
                }
            }
        });

        Map<String, String> stored = embeddings.inputHashes(model);
        Map<String, float[]> fresh = new HashMap<>();
        List<Map.Entry<String, String>> needed = new ArrayList<>();
        int cached = 0;
        for (Map.Entry<String, String> entry : textByMerchant.entrySet()) {
            if (sha256(entry.getValue()).equals(stored.get(entry.getKey()))) {
                cached++;
            } else {
                needed.add(entry);
            }
        }

        Status status = Status.DONE;
        int calls = 0;
        int callsWithoutUsage = 0;
        long inputTokens = 0;
        int done = 0;
        for (Map.Entry<String, String> entry : needed) {
            if (calls >= properties.maxEmbeddingsPerRun()) {
                status = Status.CAP_REACHED;
                break;
            }
            Embedding embedding;
            calls++;
            try {
                embedding = embedder.embed(entry.getValue());
            } catch (RuntimeException e) {
                // The message can echo the request: class only. Stage 5 goes on without the missing vectors.
                log.warn("Merchant embedding failed ({}); the rest waits for the next run", e.getClass().getName());
                status = Status.STOPPED_ON_ERROR;
                break;
            }
            if (embedding.usageReported()) {
                inputTokens += embedding.inputTokens();
            } else {
                callsWithoutUsage++;
            }
            embeddings.upsert(entry.getKey(), model, sha256(entry.getValue()), embedding.vector());
            fresh.put(entry.getKey(), embedding.vector());
            done++;
        }
        EmbeddingRunResult result = new EmbeddingRunResult(status, textByMerchant.size(), cached, done, calls,
                callsWithoutUsage, inputTokens, needed.size() - done);

        Map<String, float[]> vectors = new HashMap<>();
        for (String merchant : itemMerchants) {
            float[] vector = fresh.get(merchant);
            if (vector == null && sha256(textByMerchant.get(merchant)).equals(stored.get(merchant))) {
                vector = embeddings.find(merchant, model).map(MerchantEmbedding::embedding).orElse(null);
            }
            if (vector != null) {
                vectors.put(merchant, vector);
            }
        }
        log.info("Merchant embeddings: status={} merchants={} cached={} embedded={} calls={} callsWithoutUsage={} "
                        + "inputTokens={} remaining={}", result.status(), result.merchants(), result.cached(),
                result.embedded(), result.calls(), result.callsWithoutUsage(), result.inputTokens(),
                result.remaining());
        return new Outcome(result, Map.copyOf(vectors));
    }

    /**
     * Up to {@code k} trusted examples near an item's vector, nearest first (see the class comment).
     *
     * @param direction the sign of the item's amount
     */
    public List<SimilarMerchant> examples(String merchant, int direction, float[] vector) {
        if (properties.k() == 0 || vector == null) {
            return List.of();
        }
        return embeddings.findNearestLabeled(vector, embedder.modelId(), merchant, direction,
                properties.minSimilarity(), POOL, properties.k());
    }

    /** The embedding model's id: what a direct assignment records as its model. */
    String modelId() {
        return embedder.modelId();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
