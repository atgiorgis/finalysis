package com.finalysis.categorize;

import com.pgvector.PGvector;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One embedding per merchant and embedding model (DAT-06, V17), for finding similar merchants during categorization
 * (CAT-03, docs/plans/phase-4.md §6).
 *
 * <p>Plain JDBC, not JPA: pgvector's {@code vector} type isn't mapped by JPA in this project, and
 * similarity search is a native {@code <=>} operator query whose {@code ORDER BY ... LIMIT} shape
 * must stay intact for the HNSW index to be usable. Vectors are bound with pgvector-java's
 * {@link PGvector}, a typed {@code PGobject}, so queries need no casts and no hand-written vector
 * literal formatting or parsing.
 *
 * <p>{@code merchant} is the key: pass the exact cleaned value stored in {@code txn.merchant}. This repository does no
 * case or whitespace normalization, so "ACME" and "Acme " are different merchants; callers must be consistent.
 * {@code model} is on every row and every query: vectors of two models are never compared, even when both have
 * 1024 dimensions.
 *
 * <p>{@code @Component}, not {@code @Repository}: the latter's exception translation would turn the
 * validation {@link IllegalArgumentException}s into {@code InvalidDataAccessApiUsageException}, and
 * {@link JdbcClient} already translates SQL errors itself.
 */
@Component
public class MerchantEmbeddingRepository {

    /** Matches {@code vector(1024)} in V17. */
    public static final int DIMENSIONS = 1024;
    public static final int MAX_LIMIT = 50;

    // <=> is cosine distance, matching the vector_cosine_ops class of ix_merchant_embedding_hnsw. The index serves
    // the inner ORDER BY <distance expression> ... LIMIT; the model and self filters apply to what it returns, and
    // labels are looked up for those candidates only. A label is the category of the merchant's newest txn in the
    // item's direction whose category came from the user or a rule: never from the AI, so the model never learns
    // from its own answers. A txn marked not a transfer gives no label: its description is often generic, so its
    // category says nothing about similar merchants (step 9d notes).
    static final String NEAREST_SQL = """
            SELECT merchant, embedding <=> :embedding AS distance
            FROM merchant_embedding
            WHERE model = :model AND merchant <> :self
            ORDER BY embedding <=> :embedding
            LIMIT :pool
            """;

    static final String NEAREST_LABELED_SQL = """
            SELECT n.merchant, 1 - n.distance AS similarity, label.category
            FROM (%s) n
            JOIN LATERAL (
                SELECT c.name AS category
                FROM txn t JOIN category c ON c.id = t.category_id
                WHERE t.merchant = n.merchant
                  AND t.category_source IN ('USER', 'RULE')
                  AND NOT t.transfer_excluded
                  AND sign(t.amount) = :direction
                ORDER BY t.txn_date DESC, t.id DESC
                LIMIT 1
            ) label ON true
            WHERE 1 - n.distance >= :minSimilarity
            ORDER BY n.distance, n.merchant
            LIMIT :limit
            """.formatted(NEAREST_SQL);

    private final JdbcClient jdbc;

    public MerchantEmbeddingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the merchant's embedding for this model or replaces it. {@code created_at} is reset on replace: it
     * records when the current embedding was made.
     *
     * @param inputSha256 the SHA-256 (hex) of the text that was embedded
     */
    public void upsert(String merchant, String model, String inputSha256, float[] embedding) {
        requireNonBlank(merchant, "merchant");
        requireNonBlank(model, "model");
        requireNonBlank(inputSha256, "inputSha256");
        PGvector vector = toVector(embedding);
        jdbc.sql("""
                INSERT INTO merchant_embedding (merchant, model, dimensions, input_sha256, embedding)
                VALUES (:merchant, :model, :dimensions, :inputSha256, :embedding)
                ON CONFLICT (merchant, model) DO UPDATE
                SET dimensions = EXCLUDED.dimensions, input_sha256 = EXCLUDED.input_sha256,
                    embedding = EXCLUDED.embedding, created_at = now()
                """)
                .param("merchant", merchant)
                .param("model", model)
                .param("dimensions", DIMENSIONS)
                .param("inputSha256", inputSha256)
                .param("embedding", vector)
                .update();
    }

    public Optional<MerchantEmbedding> find(String merchant, String model) {
        requireNonBlank(merchant, "merchant");
        requireNonBlank(model, "model");
        // Read as text and parse with PGvector, so the driver needs no registered vector type.
        return jdbc.sql("""
                SELECT merchant, model, input_sha256, embedding::text AS embedding
                FROM merchant_embedding WHERE merchant = :merchant AND model = :model""")
                .param("merchant", merchant)
                .param("model", model)
                .query((rs, row) -> new MerchantEmbedding(rs.getString("merchant"), rs.getString("model"),
                        rs.getString("input_sha256"), fromText(rs.getString("embedding"))))
                .optional();
    }

    /** The SHA-256 of what was embedded, per merchant, for this model: what is cached. */
    public Map<String, String> inputHashes(String model) {
        requireNonBlank(model, "model");
        Map<String, String> hashes = new HashMap<>();
        jdbc.sql("SELECT merchant, input_sha256 FROM merchant_embedding WHERE model = :model")
                .param("model", model)
                .query(rs -> {
                    hashes.put(rs.getString("merchant"), rs.getString("input_sha256"));
                });
        return hashes;
    }

    /**
     * Up to {@code limit} merchants embedded by the same model with a trusted label in {@code direction}, nearest
     * first, at least {@code minSimilarity} (cosine similarity, 1 - distance) from the query. Only the nearest
     * {@code pool} merchants of that model are considered; {@code self} is never returned.
     *
     * @param direction the sign of the item's amount: -1 money out, 1 money in
     */
    public List<SimilarMerchant> findNearestLabeled(float[] embedding, String model, String self, int direction,
                                                    double minSimilarity, int pool, int limit) {
        PGvector vector = toVector(embedding);
        requireNonBlank(model, "model");
        requireNonBlank(self, "self");
        requireLimit(pool, "pool");
        requireLimit(limit, "limit");
        return jdbc.sql(NEAREST_LABELED_SQL)
                .param("embedding", vector)
                .param("model", model)
                .param("self", self)
                .param("pool", pool)
                .param("direction", direction)
                .param("minSimilarity", minSimilarity)
                .param("limit", limit)
                .query((rs, row) -> new SimilarMerchant(rs.getString("merchant"), rs.getString("category"),
                        rs.getDouble("similarity")))
                .list();
    }

    private static PGvector toVector(float[] embedding) {
        if (embedding == null || embedding.length != DIMENSIONS) {
            throw new IllegalArgumentException("embedding must have " + DIMENSIONS + " dimensions, had "
                    + (embedding == null ? "none" : embedding.length));
        }
        // pgvector rejects NaN and infinity; fail here with a clear message instead of a SQL error.
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("embedding values must be finite");
            }
        }
        return new PGvector(embedding);
    }

    private static float[] fromText(String text) throws SQLException {
        return new PGvector(text).toArray();
    }

    private static void requireLimit(int limit, String name) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(name + " must be between 1 and " + MAX_LIMIT + ", was " + limit);
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
