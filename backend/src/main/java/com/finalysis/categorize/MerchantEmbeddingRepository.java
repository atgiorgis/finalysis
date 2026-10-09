package com.finalysis.categorize;

import com.pgvector.PGvector;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One embedding per merchant (DAT-06), for finding similar merchants during categorization.
 *
 * <p>Plain JDBC, not JPA: pgvector's {@code vector} type isn't mapped by JPA in this project, and
 * similarity search is a native {@code <=>} operator query whose {@code ORDER BY ... LIMIT} shape
 * must stay intact for the HNSW index to be usable. Vectors are bound with pgvector-java's
 * {@link PGvector}, a typed {@code PGobject}, so queries need no casts and no hand-written vector
 * literal formatting or parsing.
 *
 * <p>{@code merchant} is the key: pass the exact cleaned value stored in {@code txn.merchant}, the
 * canonical name produced by ingestion's merchant cleanup. This repository does no case or
 * whitespace normalization, so "ACME" and "Acme " are different merchants; callers must be
 * consistent.
 *
 * <p>{@code @Component}, not {@code @Repository}: the latter's exception translation would turn the
 * validation {@link IllegalArgumentException}s into {@code InvalidDataAccessApiUsageException}, and
 * {@link JdbcClient} already translates SQL errors itself.
 */
@Component
public class MerchantEmbeddingRepository {

    /** Matches {@code vector(1024)} in V1. */
    public static final int DIMENSIONS = 1024;
    public static final int MAX_LIMIT = 50;

    // <=> is cosine distance, matching the vector_cosine_ops class of ix_merchant_embedding_hnsw.
    // The index can only serve ORDER BY <distance expression> ascending with a LIMIT.
    static final String MOST_SIMILAR_SQL = """
            SELECT merchant, embedding <=> :embedding AS distance
            FROM merchant_embedding
            ORDER BY embedding <=> :embedding
            LIMIT :limit
            """;

    private final JdbcClient jdbc;

    public MerchantEmbeddingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the merchant's embedding or replaces it. {@code created_at} is reset on replace: it
     * records when the current embedding was made.
     */
    public void upsert(String merchant, float[] embedding, String model) {
        requireNonBlank(merchant, "merchant");
        requireNonBlank(model, "model");
        PGvector vector = toVector(embedding);
        jdbc.sql("""
                INSERT INTO merchant_embedding (merchant, embedding, model)
                VALUES (:merchant, :embedding, :model)
                ON CONFLICT (merchant) DO UPDATE
                SET embedding = EXCLUDED.embedding, model = EXCLUDED.model, created_at = now()
                """)
                .param("merchant", merchant)
                .param("embedding", vector)
                .param("model", model)
                .update();
    }

    public Optional<MerchantEmbedding> findByMerchant(String merchant) {
        requireNonBlank(merchant, "merchant");
        // Read as text and parse with PGvector, so the driver needs no registered vector type.
        return jdbc.sql("SELECT merchant, embedding::text AS embedding, model FROM merchant_embedding WHERE merchant = :merchant")
                .param("merchant", merchant)
                .query((rs, row) -> new MerchantEmbedding(
                        rs.getString("merchant"), fromText(rs.getString("embedding")), rs.getString("model")))
                .optional();
    }

    /** Up to {@code limit} merchants, nearest first by cosine distance. */
    public List<SimilarMerchant> findMostSimilar(float[] embedding, int limit) {
        PGvector vector = toVector(embedding);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ", was " + limit);
        }
        return jdbc.sql(MOST_SIMILAR_SQL)
                .param("embedding", vector)
                .param("limit", limit)
                .query((rs, row) -> new SimilarMerchant(rs.getString("merchant"), rs.getDouble("distance")))
                .list();
    }

    /** Merchants embedded by a model other than {@code currentModel}, to re-embed after a model switch. */
    public List<String> findStale(String currentModel) {
        requireNonBlank(currentModel, "currentModel");
        return jdbc.sql("SELECT merchant FROM merchant_embedding WHERE model <> :model ORDER BY merchant")
                .param("model", currentModel)
                .query(String.class)
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

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
