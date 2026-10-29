package com.finalysis.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.support.IntegrationTest;
import com.pgvector.PGvector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V25 dropped the HNSW indexes for an exact scan per model (docs/plans/phase-9.md §4.1, §16): a household has hundreds
 * to a few thousand merchants. This pins it at 5,000: chat's merchant search over 5,000 nomic-embed-text vectors (768),
 * beside 5,000 of another model (Titan V2, 1024) that the scan must skip, answers in under 50 ms on Testcontainers
 * (the median of ten runs, after a warm-up), and finds the exact nearest.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantSearchTimingTest {

    private static final int VECTORS = 5_000;
    private static final String NOMIC = "nomic-embed-text";
    private static final String TITAN = "amazon.titan-embed-text-v2:0";

    private final MerchantSearchQuery search;
    private final JdbcTemplate jdbc;
    private final List<float[]> nomic = new ArrayList<>();

    MerchantSearchTimingTest(MerchantSearchQuery search, JdbcTemplate jdbc) {
        this.search = search;
        this.jdbc = jdbc;
    }

    @BeforeAll
    void seed() {
        Random random = new Random(9);
        insert(NOMIC, 768, random, nomic);
        insert(TITAN, 1024, random, new ArrayList<>());
        jdbc.execute("ANALYZE search_embedding");
    }

    @AfterAll
    void cleanUp() {
        jdbc.update("DELETE FROM search_embedding");
    }

    private void insert(String model, int length, Random random, List<float[]> kept) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < VECTORS; i++) {
            float[] vector = new float[length];
            for (int d = 0; d < length; d++) {
                vector[d] = random.nextFloat() - 0.5f;
            }
            kept.add(vector);
            rows.add(new Object[] {"MERCHANT " + i, model, length, "a".repeat(64), new PGvector(vector).getValue()});
        }
        jdbc.batchUpdate("""
                INSERT INTO search_embedding (kind, subject, model, dimensions, input_sha256, embedding)
                VALUES ('MERCHANT', ?, ?, ?, ?, ?::vector)""", rows);
    }

    @Test
    void searchingFiveThousandVectorsOfOneModelTakesUnderFiftyMilliseconds() {
        float[] query = nomic.get(4_242);
        for (int i = 0; i < 3; i++) {
            search.nearest(query, NOMIC, 0.0);
        }
        long[] millis = new long[10];
        for (int i = 0; i < millis.length; i++) {
            long start = System.nanoTime();
            search.nearest(query, NOMIC, 0.0);
            millis[i] = (System.nanoTime() - start) / 1_000_000;
        }
        Arrays.sort(millis);

        assertThat(millis[millis.length / 2]).as("median of %s ms", Arrays.toString(millis)).isLessThan(50);
    }

    /** The scan is exact and per model: the stored vector itself is the nearest, at similarity 1. */
    @Test
    void theScanIsExact() {
        List<String> nearest = jdbc.queryForList("""
                SELECT subject FROM search_embedding WHERE kind = 'MERCHANT' AND model = ?
                ORDER BY embedding <=> ?::vector LIMIT 3""", String.class, NOMIC, new PGvector(nomic.get(4_242)).getValue());

        assertThat(nearest.getFirst()).isEqualTo("MERCHANT 4242");
    }
}
