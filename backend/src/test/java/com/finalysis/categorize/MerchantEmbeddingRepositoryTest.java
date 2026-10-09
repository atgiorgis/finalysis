package com.finalysis.categorize;

import static com.finalysis.categorize.MerchantEmbeddingRepository.DIMENSIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import com.finalysis.support.IntegrationTest;
import com.pgvector.PGvector;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional // each test's embeddings roll back
class MerchantEmbeddingRepositoryTest {

    private final MerchantEmbeddingRepository embeddings;
    private final JdbcClient jdbc;

    MerchantEmbeddingRepositoryTest(MerchantEmbeddingRepository embeddings, JdbcClient jdbc) {
        this.embeddings = embeddings;
        this.jdbc = jdbc;
    }

    @Test
    void upsertThenFindRoundTripsVectorAndModel() {
        float[] vector = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = (float) Math.sin(i * 0.37) / 3; // varied signs and magnitudes
        }

        embeddings.upsert("Corner Market", vector, "model-v1");

        MerchantEmbedding found = embeddings.findByMerchant("Corner Market").orElseThrow();
        assertThat(found.merchant()).isEqualTo("Corner Market");
        assertThat(found.model()).isEqualTo("model-v1");
        assertThat(found.embedding()).containsExactly(vector, within(1e-6f));
    }

    @Test
    void findUnknownMerchantIsEmpty() {
        assertThat(embeddings.findByMerchant("Nobody")).isEmpty();
    }

    @Test
    void upsertingSameMerchantTwiceUpdatesInsteadOfDuplicating() {
        embeddings.upsert("Acme", unit(0), "model-v1");
        embeddings.upsert("Acme", unit(1), "model-v2");

        assertThat(countRows("Acme")).isEqualTo(1);
        MerchantEmbedding found = embeddings.findByMerchant("Acme").orElseThrow();
        assertThat(found.embedding()).containsExactly(unit(1), within(1e-6f));
        assertThat(found.model()).isEqualTo("model-v2");
    }

    @Test
    void findMostSimilarOrdersByAscendingCosineDistance() {
        embeddings.upsert("Far", unit(2), "model-v1");
        embeddings.upsert("Near", blend(unit(0), unit(1), 0.05f), "model-v1"); // close to Exact
        embeddings.upsert("Exact", unit(0), "model-v1");

        List<SimilarMerchant> similar = embeddings.findMostSimilar(unit(0), 3);

        assertThat(similar).extracting(SimilarMerchant::merchant).containsExactly("Exact", "Near", "Far");
        assertThat(similar).extracting(SimilarMerchant::distance).isSorted().doesNotHaveDuplicates();
        assertThat(similar.get(0).distance()).isCloseTo(0.0, within(1e-6));
        assertThat(similar.get(1).distance()).isLessThan(0.01);
        assertThat(similar.get(2).distance()).isCloseTo(1.0, within(1e-6)); // orthogonal
    }

    @Test
    void findMostSimilarRespectsLimit() {
        embeddings.upsert("Far", unit(2), "model-v1");
        embeddings.upsert("Near", blend(unit(0), unit(1), 0.05f), "model-v1");
        embeddings.upsert("Exact", unit(0), "model-v1");

        assertThat(embeddings.findMostSimilar(unit(0), 2))
                .extracting(SimilarMerchant::merchant).containsExactly("Exact", "Near");
    }

    @Test
    void findStaleReturnsOnlyMerchantsFromOtherModels() {
        embeddings.upsert("Old One", unit(0), "model-v1");
        embeddings.upsert("Old Two", unit(1), "model-v1");
        embeddings.upsert("Current", unit(2), "model-v2");

        assertThat(embeddings.findStale("model-v2")).containsExactly("Old One", "Old Two");
        assertThat(embeddings.findStale("model-v1")).containsExactly("Current");
    }

    @Test
    void similarityQueryCanUseHnswIndex() {
        embeddings.upsert("Exact", unit(0), "model-v1");
        // On a table this small the planner prefers a seq scan, so rule it out for this transaction.
        // This proves the query and the index use the same operator class, not that the index wins at scale.
        jdbc.sql("SET LOCAL enable_seqscan = off").update();

        List<String> plan = jdbc.sql("EXPLAIN " + MerchantEmbeddingRepository.MOST_SIMILAR_SQL)
                .param("embedding", new PGvector(unit(0)))
                .param("limit", 5)
                .query(String.class)
                .list();

        assertThat(String.join("\n", plan)).contains("ix_merchant_embedding_hnsw");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, DIMENSIONS - 1, DIMENSIONS + 1})
    void wrongDimensionsThrow(int dimensions) {
        float[] vector = new float[dimensions];

        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.upsert("Acme", vector, "model-v1"));
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.findMostSimilar(vector, 5));
    }

    @Test
    void nullEmbeddingThrows() {
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.upsert("Acme", null, "model-v1"));
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.findMostSimilar(null, 5));
    }

    @ParameterizedTest
    @ValueSource(floats = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
    void nonFiniteValueThrows(float value) {
        float[] vector = unit(0);
        vector[7] = value;

        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.upsert("Acme", vector, "model-v1"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void blankMerchantOrModelThrows(String blank) {
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.upsert(blank, unit(0), "model-v1"));
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.findByMerchant(blank));
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.upsert("Acme", unit(0), blank));
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.findStale(blank));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, MerchantEmbeddingRepository.MAX_LIMIT + 1})
    void outOfRangeLimitThrows(int limit) {
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.findMostSimilar(unit(0), limit));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, MerchantEmbeddingRepository.MAX_LIMIT})
    void boundaryLimitsAreAccepted(int limit) {
        assertThat(embeddings.findMostSimilar(unit(0), limit)).isEmpty();
    }

    private int countRows(String merchant) {
        return jdbc.sql("SELECT count(*) FROM merchant_embedding WHERE merchant = :merchant")
                .param("merchant", merchant)
                .query(Integer.class)
                .single();
    }

    private static float[] unit(int index) {
        float[] vector = new float[DIMENSIONS];
        vector[index] = 1f;
        return vector;
    }

    /** {@code base + weight * offset}, element-wise. */
    private static float[] blend(float[] base, float[] offset, float weight) {
        float[] vector = base.clone();
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] += weight * offset[i];
        }
        return vector;
    }
}
