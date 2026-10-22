package com.finalysis.categorize;

import com.finalysis.ai.SearchDocuments;
import com.pgvector.PGvector;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes the chat search index (V23 {@code search_embedding}; docs/plans/phase-6.md step 8b): one vector per merchant
 * or category and embedding model, with the SHA-256 of the document embedded. Only {@link MerchantIndexRefresh} uses
 * it; chat reads the table with its own read-only SQL and may not reach this class (SEC-03, it writes).
 */
@Component
public class SearchEmbeddingRepository {

    private final JdbcClient jdbc;

    public SearchEmbeddingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the subject's vector for this model or replaces it; {@code created_at} is reset on replace. */
    public void upsert(SearchDocuments.Kind kind, String subject, String model, String inputSha256, float[] embedding) {
        if (subject == null || subject.isBlank() || model == null || model.isBlank() || inputSha256 == null) {
            throw new IllegalArgumentException("subject, model and inputSha256 are required");
        }
        if (embedding == null || embedding.length != MerchantEmbeddingRepository.DIMENSIONS) {
            throw new IllegalArgumentException("embedding must have " + MerchantEmbeddingRepository.DIMENSIONS
                    + " dimensions");
        }
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("embedding values must be finite");
            }
        }
        jdbc.sql("""
                INSERT INTO search_embedding (kind, subject, model, dimensions, input_sha256, embedding)
                VALUES (:kind, :subject, :model, :dimensions, :inputSha256, :embedding)
                ON CONFLICT (kind, subject, model) DO UPDATE
                SET dimensions = EXCLUDED.dimensions, input_sha256 = EXCLUDED.input_sha256,
                    embedding = EXCLUDED.embedding, created_at = now()
                """)
                .param("kind", kind.name())
                .param("subject", subject)
                .param("model", model)
                .param("dimensions", MerchantEmbeddingRepository.DIMENSIONS)
                .param("inputSha256", inputSha256)
                .param("embedding", new PGvector(embedding))
                .update();
    }

    /** The SHA-256 of what was embedded, per subject of this kind, for this model: what is cached. */
    public Map<String, String> inputHashes(SearchDocuments.Kind kind, String model) {
        Map<String, String> hashes = new HashMap<>();
        jdbc.sql("SELECT subject, input_sha256 FROM search_embedding WHERE kind = :kind AND model = :model")
                .param("kind", kind.name())
                .param("model", model)
                .query(rs -> {
                    hashes.put(rs.getString("subject"), rs.getString("input_sha256"));
                });
        return hashes;
    }
}
