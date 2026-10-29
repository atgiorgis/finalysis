package com.finalysis.chat.tool;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chat's read-only SQL over the search index (CHT-02; docs/plans/phase-6.md §4.3, step 8b: V23's
 * {@code search_embedding}): the nearest merchants and the nearest categories to a search vector, the index's coverage,
 * and the distinct merchants the turn's directory is built from. It never writes: the index is filled by
 * categorization's {@code MerchantIndexRefresh}, which chat may not reach (SEC-03).
 *
 * <p>Only vectors of the query's own embedding model are compared ({@code model} on every query), even when two models
 * share a dimension count. The vector is bound as pgvector's text form and cast, so the tools need no driver type.
 * Since V25 the search is an exact scan of the model's rows (docs/plans/phase-9.md §4.1).
 */
@Component
public class MerchantSearchQuery {

    /**
     * Neighbours taken before the similarity floor: 40, what pgvector's HNSW scan returned until V25 dropped the index
     * for an exact scan, so a search finds as many candidates as before.
     */
    static final int POOL = 40;

    static final String NEAREST_SQL = """
            SELECT subject AS merchant, round((1 - distance)::numeric, 2) AS similarity
            FROM (
                SELECT subject, embedding <=> CAST(:embedding AS vector) AS distance
                FROM search_embedding
                WHERE kind = 'MERCHANT' AND model = :model
                ORDER BY embedding <=> CAST(:embedding AS vector)
                LIMIT :pool
            ) n
            WHERE 1 - distance >= :minSimilarity
              AND EXISTS (SELECT 1 FROM txn t WHERE t.merchant = n.subject)
            ORDER BY distance, subject
            """;

    /** Categories still in the data; few, so the index scan's pool covers them all. */
    static final String NEAREST_CATEGORIES_SQL = """
            SELECT subject AS category, round((1 - distance)::numeric, 2) AS similarity
            FROM (
                SELECT subject, embedding <=> CAST(:embedding AS vector) AS distance
                FROM search_embedding
                WHERE kind = 'CATEGORY' AND model = :model
                ORDER BY embedding <=> CAST(:embedding AS vector)
                LIMIT :pool
            ) n
            WHERE 1 - distance >= :minSimilarity
              AND EXISTS (SELECT 1 FROM category c WHERE c.name = n.subject)
            ORDER BY distance, subject
            """;

    private final JdbcClient jdbc;

    public MerchantSearchQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A stored merchant near the query, with its cosine similarity to two decimals. */
    public record Hit(String merchant, BigDecimal similarity) {
    }

    /** How much of the data the index covers: distinct merchants, and those with a vector of the model. */
    public record Coverage(int merchants, int indexed) {
    }

    /** Stored merchants at least {@code minSimilarity} from the vector, nearest first, at most {@value #POOL}. */
    @Transactional(readOnly = true)
    public List<Hit> nearest(float[] vector, String model, double minSimilarity) {
        return jdbc.sql(NEAREST_SQL)
                .param("embedding", vectorText(vector))
                .param("model", model)
                .param("pool", POOL)
                .param("minSimilarity", minSimilarity)
                .query((rs, row) -> new Hit(rs.getString("merchant"), rs.getBigDecimal("similarity")))
                .list();
    }

    /** A category near the query, with its cosine similarity to two decimals. */
    public record CategoryHit(String category, BigDecimal similarity) {
    }

    /** Categories at least {@code minSimilarity} from the vector, nearest first, at most {@value #POOL}. */
    @Transactional(readOnly = true)
    public List<CategoryHit> nearestCategories(float[] vector, String model, double minSimilarity) {
        return jdbc.sql(NEAREST_CATEGORIES_SQL)
                .param("embedding", vectorText(vector))
                .param("model", model)
                .param("pool", POOL)
                .param("minSimilarity", minSimilarity)
                .query((rs, row) -> new CategoryHit(rs.getString("category"), rs.getBigDecimal("similarity")))
                .list();
    }

    /** Counted in SQL (§4.3 point 6), so the model can say results may be incomplete. */
    @Transactional(readOnly = true)
    public Coverage coverage(String model) {
        return jdbc.sql("""
                SELECT count(*) AS merchants,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM search_embedding e
                                                      WHERE e.kind = 'MERCHANT' AND e.subject = m.merchant
                                                        AND e.model = :model)) AS indexed
                FROM (SELECT DISTINCT merchant FROM txn WHERE merchant IS NOT NULL) m""")
                .param("model", model)
                .query((rs, row) -> new Coverage(rs.getInt("merchants"), rs.getInt("indexed")))
                .single();
    }

    /** Every distinct stored merchant, in name order: what a turn's directory decides once. */
    @Transactional(readOnly = true)
    public List<String> merchants() {
        return jdbc.sql("SELECT DISTINCT merchant FROM txn WHERE merchant IS NOT NULL ORDER BY merchant")
                .query(String.class).list();
    }

    /** pgvector's text form, {@code [0.1,-0.2,...]}; {@link Float#toString} round-trips every float. */
    static String vectorText(float[] vector) {
        StringBuilder text = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) {
                throw new IllegalArgumentException("a search vector's values must be finite");
            }
            text.append(i == 0 ? "" : ",").append(vector[i]);
        }
        return text.append(']').toString();
    }
}
