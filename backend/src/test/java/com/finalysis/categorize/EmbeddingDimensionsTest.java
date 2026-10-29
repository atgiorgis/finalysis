package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ai.SearchDocuments;
import com.finalysis.support.IntegrationTest;
import com.pgvector.PGvector;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V25 (docs/plans/phase-9.md §4.1) on Testcontainers: both embedding tables hold Titan V2's 1024-length vectors and
 * nomic-embed-text's 768-length ones side by side, each row's length must equal its {@code dimensions}, and a search
 * compares one model's vectors only. The timing at 5,000 vectors is {@code MerchantSearchTimingTest}'s.
 */
@IntegrationTest
class EmbeddingDimensionsTest {

    private static final String TITAN = "amazon.titan-embed-text-v2:0";
    private static final String NOMIC = "nomic-embed-text";
    private static final String HASH = "a".repeat(64);

    private final MerchantEmbeddingRepository merchants;
    private final SearchEmbeddingRepository search;
    private final JdbcTemplate jdbc;
    private final JdbcClient client;

    EmbeddingDimensionsTest(MerchantEmbeddingRepository merchants, SearchEmbeddingRepository search, JdbcTemplate jdbc,
                            JdbcClient client) {
        this.merchants = merchants;
        this.search = search;
        this.jdbc = jdbc;
        this.client = client;
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM merchant_embedding");
        jdbc.update("DELETE FROM search_embedding");
    }

    /** A unit vector along {@code axis}, of this length. */
    private static float[] unit(int length, int axis) {
        float[] vector = new float[length];
        vector[axis] = 1f;
        return vector;
    }

    @Test
    void bothLengthsAreStoredSideBySideWithTheirDimensions() {
        merchants.upsert("ACME", TITAN, HASH, unit(1024, 0));
        merchants.upsert("ACME", NOMIC, HASH, unit(768, 0));
        search.upsert(SearchDocuments.Kind.CATEGORY, "Dining", TITAN, HASH, unit(1024, 1));
        search.upsert(SearchDocuments.Kind.CATEGORY, "Dining", NOMIC, HASH, unit(768, 1));

        assertThat(merchants.find("ACME", TITAN).orElseThrow().embedding()).hasSize(1024);
        assertThat(merchants.find("ACME", NOMIC).orElseThrow().embedding()).hasSize(768);
        assertThat(jdbc.queryForList("SELECT model || ' ' || dimensions FROM merchant_embedding ORDER BY model",
                String.class)).containsExactly(TITAN + " 1024", NOMIC + " 768");
        assertThat(jdbc.queryForList("SELECT model || ' ' || dimensions FROM search_embedding ORDER BY model",
                String.class)).containsExactly(TITAN + " 1024", NOMIC + " 768");
    }

    /** The constraint, not only the app: a row whose vector isn't its stated length is refused. */
    @Test
    void aRowWhoseLengthIsNotItsDimensionsIsRefused() {
        String vector1024 = new PGvector(unit(1024, 0)).getValue();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO merchant_embedding (merchant, model, dimensions, input_sha256, embedding)
                VALUES ('ACME', ?, 768, ?, ?::vector)""", NOMIC, HASH, vector1024))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_merchant_embedding_dimensions");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO search_embedding (kind, subject, model, dimensions, input_sha256, embedding)
                VALUES ('CATEGORY', 'Dining', ?, 768, ?, ?::vector)""", NOMIC, HASH, vector1024))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_search_embedding_dimensions");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO merchant_embedding (merchant, model, dimensions, input_sha256, embedding)
                VALUES ('ACME', ?, 0, ?, '[]'::vector)""", NOMIC, HASH))
                .isInstanceOf(RuntimeException.class);
    }

    /** Only the query's model is compared: another model's vectors, of another length, are never read. */
    @Test
    void aSearchComparesOneModelOnly() {
        merchants.upsert("ALPHA", NOMIC, HASH, unit(768, 0));
        merchants.upsert("BETA", NOMIC, HASH, unit(768, 1));
        merchants.upsert("ALPHA", TITAN, HASH, unit(1024, 0));
        merchants.upsert("GAMMA", TITAN, HASH, unit(1024, 2));

        assertThat(nearest(unit(768, 0), NOMIC)).containsExactly("ALPHA", "BETA");
        assertThat(nearest(unit(1024, 2), TITAN)).containsExactly("GAMMA", "ALPHA");
    }

    private List<String> nearest(float[] query, String model) {
        return client.sql(MerchantEmbeddingRepository.NEAREST_SQL)
                .param("embedding", new PGvector(query))
                .param("model", model)
                .param("self", "NONE")
                .param("pool", 40)
                .query((rs, row) -> rs.getString("merchant"))
                .list();
    }
}
