package com.finalysis.categorize;

import static com.finalysis.categorize.MerchantEmbeddingRepository.DIMENSIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.finalysis.support.IntegrationTest;
import com.pgvector.PGvector;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link MerchantEmbeddingRepository} and V17 (DAT-06, CAT-03; docs/plans/phase-4.md §6, step 8 notes) on pgvector:
 * one vector per merchant and model, the nearest-neighbour query (same model only, trusted labels only, in the item's
 * direction, above a floor) and that it can use the HNSW index.
 */
@IntegrationTest
@Transactional // each test's rows roll back
class MerchantEmbeddingRepositoryTest {

    private static final String MODEL = "model-v1";
    private static final String HASH = MerchantEmbeddingStage.sha256("TEXT");

    private final MerchantEmbeddingRepository embeddings;
    private final JdbcClient jdbc;
    private final CategorizeRows rows;

    MerchantEmbeddingRepositoryTest(MerchantEmbeddingRepository embeddings, JdbcClient jdbc, JdbcTemplate template,
                                    MerchantCleaner cleaner) {
        this.embeddings = embeddings;
        this.jdbc = jdbc;
        this.rows = new CategorizeRows(template, cleaner);
    }

    @BeforeEach
    void noOtherEmbeddings() {
        jdbc.sql("DELETE FROM merchant_embedding").update(); // rolled back with the test
    }

    // ------------------------------------------------------------------ storing

    @Test
    void upsertThenFindRoundTripsVectorModelAndHash() {
        float[] vector = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = (float) Math.sin(i * 0.37) / 3; // varied signs and magnitudes
        }

        embeddings.upsert("CORNER MARKET", MODEL, HASH, vector);

        MerchantEmbedding found = embeddings.find("CORNER MARKET", MODEL).orElseThrow();
        assertThat(found.merchant()).isEqualTo("CORNER MARKET");
        assertThat(found.model()).isEqualTo(MODEL);
        assertThat(found.inputSha256()).isEqualTo(HASH);
        assertThat(found.embedding()).containsExactly(vector, within(1e-6f));
        assertThat(jdbc.sql("SELECT dimensions FROM merchant_embedding").query(Integer.class).single())
                .isEqualTo(DIMENSIONS);
    }

    @Test
    void findUnknownMerchantOrModelIsEmpty() {
        embeddings.upsert("ACME", MODEL, HASH, unit(0));

        assertThat(embeddings.find("NOBODY", MODEL)).isEmpty();
        assertThat(embeddings.find("ACME", "model-v2")).isEmpty();
    }

    @Test
    void oneRowPerMerchantAndModel() {
        String otherHash = MerchantEmbeddingStage.sha256("OTHER TEXT");
        embeddings.upsert("ACME", MODEL, HASH, unit(0));
        embeddings.upsert("ACME", MODEL, otherHash, unit(1)); // replaces
        embeddings.upsert("ACME", "model-v2", HASH, unit(2)); // another model: its own row

        assertThat(countRows("ACME")).isEqualTo(2);
        assertThat(embeddings.find("ACME", MODEL).orElseThrow().embedding()).containsExactly(unit(1), within(1e-6f));
        assertThat(embeddings.find("ACME", MODEL).orElseThrow().inputSha256()).isEqualTo(otherHash);
        assertThat(embeddings.find("ACME", "model-v2").orElseThrow().embedding()).containsExactly(unit(2),
                within(1e-6f));
    }

    @Test
    void inputHashesArePerModel() {
        embeddings.upsert("ACME", MODEL, HASH, unit(0));
        embeddings.upsert("BOLT", MODEL, HASH, unit(1));
        embeddings.upsert("ACME", "model-v2", MerchantEmbeddingStage.sha256("ACME"), unit(2));

        assertThat(embeddings.inputHashes(MODEL)).isEqualTo(Map.of("ACME", HASH, "BOLT", HASH));
        assertThat(embeddings.inputHashes("model-v2")).isEqualTo(Map.of("ACME", MerchantEmbeddingStage.sha256("ACME")));
        assertThat(embeddings.inputHashes("model-v3")).isEmpty();
    }

    // ------------------------------------------------------------------ V17's constraints

    /** One bad row per case: a failed statement aborts the test's transaction. */
    @ParameterizedTest
    @CsvSource({
            "ACME, model-v1, 768,  ck_merchant_embedding_dimensions",
            "ACME, model-v1, 1024, ck_merchant_embedding_input_sha256",
            "'',   model-v1, 1024, ck_merchant_embedding_merchant",
            "ACME, '',       1024, ck_merchant_embedding_model"})
    void theSchemaRejectsABadRow(String merchant, String model, int dimensions, String constraint) {
        String hash = constraint.endsWith("input_sha256") ? "abc" : HASH;

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO merchant_embedding (merchant, model, dimensions, input_sha256, embedding)
                VALUES (?, ?, ?, ?, ?)""").params(merchant, model, dimensions, hash, new PGvector(unit(0))).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    @Test
    void theKeyIsMerchantAndModel() {
        List<String> key = jdbc.sql("""
                SELECT a.attname FROM pg_index i
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
                WHERE i.indrelid = 'merchant_embedding'::regclass AND i.indisprimary
                ORDER BY array_position(i.indkey, a.attnum)""").query(String.class).list();

        assertThat(key).containsExactly("merchant", "model");
    }

    // ------------------------------------------------------------------ nearest labeled merchants

    /** A txn of this merchant with a category and source, money out unless the amount says otherwise. */
    private void labeled(String merchant, String category, String source, String amount) {
        long id = rows.txn(merchant, amount);
        rows.categorize(id, category, source);
    }

    @Test
    void nearestLabeledIsNearestFirstWithItsSimilarity() {
        embeddings.upsert("FAR", MODEL, HASH, blend(unit(0), unit(1), 1.0f));      // 0.71
        embeddings.upsert("NEAR", MODEL, HASH, blend(unit(0), unit(1), 0.05f));    // 0.999
        embeddings.upsert("EXACT", MODEL, HASH, unit(0));
        labeled("FAR", "Dining", "USER", "-1.00");
        labeled("NEAR", "Groceries", "RULE", "-1.00");
        labeled("EXACT", "Shopping", "USER", "-1.00");

        List<SimilarMerchant> similar = embeddings.findNearestLabeled(unit(0), MODEL, "ITEM", -1, 0.0, 40, 5);

        assertThat(similar).extracting(SimilarMerchant::merchant).containsExactly("EXACT", "NEAR", "FAR");
        assertThat(similar).extracting(SimilarMerchant::category).containsExactly("Shopping", "Groceries", "Dining");
        assertThat(similar.get(0).similarity()).isCloseTo(1.0, within(1e-6));
        assertThat(similar.get(1).similarity()).isGreaterThan(0.99);
        assertThat(similar.get(2).similarity()).isCloseTo(Math.sqrt(0.5), within(1e-4));
    }

    @Test
    void onlyUserAndRuleLabelsCountAndTheNewestOneIsUsed() {
        embeddings.upsert("AI ONLY", MODEL, HASH, unit(0));
        embeddings.upsert("UNLABELED", MODEL, HASH, unit(0));
        embeddings.upsert("MIXED", MODEL, HASH, blend(unit(0), unit(1), 0.1f));
        labeled("AI ONLY", "Dining", "AI", "-1.00");
        rows.txn("UNLABELED", "-1.00");
        rows.categorize(rows.txn("MIXED", "-1.00", "2026-04-01"), "Groceries", "USER");
        rows.categorize(rows.txn("MIXED", "-1.00", "2026-04-20"), "Dining", "RULE");  // newest trusted label
        rows.categorize(rows.txn("MIXED", "-1.00", "2026-04-25"), "Shopping", "AI");  // newer, but the AI's

        assertThat(embeddings.findNearestLabeled(unit(0), MODEL, "ITEM", -1, 0.0, 40, 5))
                .extracting(s -> s.merchant() + "=" + s.category()).containsExactly("MIXED=Dining");
    }

    /** A txn marked not a transfer gives no label (step 9d): its generic description says nothing about neighbours. */
    @Test
    void aTxnMarkedNotATransferGivesNoLabel() {
        embeddings.upsert("ONLINE BANKING TRANSFER", MODEL, HASH, unit(0));
        embeddings.upsert("MIXED", MODEL, HASH, blend(unit(0), unit(1), 0.1f));
        long excluded = rows.txn("ONLINE BANKING TRANSFER", "-1.00");
        rows.categorize(excluded, "Housing", "USER");
        rows.categorize(rows.txn("MIXED", "-1.00", "2026-04-01"), "Groceries", "USER");
        long newer = rows.txn("MIXED", "-1.00", "2026-04-20");
        rows.categorize(newer, "Dining", "USER");
        jdbc.sql("UPDATE txn SET transfer_excluded = true WHERE id IN (:excluded, :newer)")
                .param("excluded", excluded).param("newer", newer).update();

        assertThat(embeddings.findNearestLabeled(unit(0), MODEL, "ITEM", -1, 0.0, 40, 5))
                .extracting(s -> s.merchant() + "=" + s.category()).containsExactly("MIXED=Groceries");
    }

    @Test
    void theLabelIsInTheItemsDirection() {
        embeddings.upsert("STORE", MODEL, HASH, unit(0));
        labeled("STORE", "Shopping", "USER", "-20.00");
        labeled("STORE", "Refunds & Rebates", "USER", "20.00");
        embeddings.upsert("PAYER", MODEL, HASH, unit(0));
        labeled("PAYER", "Income", "RULE", "500.00"); // money in only

        assertThat(embeddings.findNearestLabeled(unit(0), MODEL, "ITEM", -1, 0.0, 40, 5))
                .extracting(s -> s.merchant() + "=" + s.category()).containsExactly("STORE=Shopping");
        assertThat(embeddings.findNearestLabeled(unit(0), MODEL, "ITEM", 1, 0.0, 40, 5))
                .extracting(s -> s.merchant() + "=" + s.category())
                .containsExactlyInAnyOrder("STORE=Refunds & Rebates", "PAYER=Income");
    }

    @Test
    void onlyTheSameModelIsComparedAndTheItemItselfIsNever() {
        embeddings.upsert("OTHER MODEL", "model-v2", HASH, unit(0));
        embeddings.upsert("ITEM", MODEL, HASH, unit(0));
        embeddings.upsert("SAME MODEL", MODEL, HASH, blend(unit(0), unit(1), 0.5f));
        labeled("OTHER MODEL", "Dining", "USER", "-1.00");
        labeled("ITEM", "Dining", "USER", "-1.00");
        labeled("SAME MODEL", "Dining", "USER", "-1.00");

        assertThat(embeddings.findNearestLabeled(unit(0), MODEL, "ITEM", -1, 0.0, 40, 5))
                .extracting(SimilarMerchant::merchant).containsExactly("SAME MODEL");
        assertThat(embeddings.findNearestLabeled(unit(0), "model-v2", "ITEM", -1, 0.0, 40, 5))
                .extracting(SimilarMerchant::merchant).containsExactly("OTHER MODEL");
    }

    @Test
    void theFloorAndTheLimitApply() {
        embeddings.upsert("ABOVE", MODEL, HASH, blend(unit(0), unit(1), 0.5f));   // 0.894
        embeddings.upsert("AT", MODEL, HASH, blend(unit(0), unit(1), 1.0f));      // 0.707
        embeddings.upsert("BELOW", MODEL, HASH, blend(unit(0), unit(1), 2.0f));   // 0.447
        embeddings.upsert("ORTHOGONAL", MODEL, HASH, unit(1));                    // 0
        for (String merchant : List.of("ABOVE", "AT", "BELOW", "ORTHOGONAL")) {
            labeled(merchant, "Dining", "USER", "-1.00");
        }

        assertThat(embeddings.findNearestLabeled(unit(0), MODEL, "ITEM", -1, 0.70, 40, 5))
                .extracting(SimilarMerchant::merchant).containsExactly("ABOVE", "AT");
        assertThat(embeddings.findNearestLabeled(unit(0), MODEL, "ITEM", -1, 0.0, 40, 3))
                .extracting(SimilarMerchant::merchant).containsExactly("ABOVE", "AT", "BELOW");
        assertThat(embeddings.findNearestLabeled(unit(0), MODEL, "ITEM", -1, 0.0, 2, 5))
                .as("only the pool the index returns is considered").extracting(SimilarMerchant::merchant)
                .containsExactly("ABOVE", "AT");
    }

    /** V25: an exact scan of the model's rows; no vector index takes part (phase-9.md §4.1). */
    @Test
    void theNearestQueryIsAnExactScanOfOneModel() {
        embeddings.upsert("EXACT", MODEL, HASH, unit(0));

        for (String sql : List.of(MerchantEmbeddingRepository.NEAREST_SQL,
                MerchantEmbeddingRepository.NEAREST_LABELED_SQL)) {
            List<String> plan = jdbc.sql("EXPLAIN " + sql)
                    .param("embedding", new PGvector(unit(0)))
                    .param("model", MODEL)
                    .param("self", "ITEM")
                    .param("pool", 40)
                    .param("direction", -1)
                    .param("minSimilarity", 0.65)
                    .param("limit", 5)
                    .query(String.class)
                    .list();

            assertThat(String.join("\n", plan)).doesNotContain("hnsw").contains("model");
        }
    }

    // ------------------------------------------------------------------ arguments

    @ParameterizedTest
    @ValueSource(ints = {0, MerchantEmbeddingRepository.MAX_DIMENSIONS + 1})
    void wrongDimensionsThrow(int dimensions) {
        float[] vector = new float[dimensions];

        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.upsert("ACME", MODEL, HASH, vector));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> embeddings.findNearestLabeled(vector, MODEL, "ITEM", -1, 0.5, 40, 5));
    }

    @Test
    void nullEmbeddingThrows() {
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.upsert("ACME", MODEL, HASH, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> embeddings.findNearestLabeled(null, MODEL, "ITEM", -1, 0.5, 40, 5));
    }

    @ParameterizedTest
    @ValueSource(floats = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
    void nonFiniteValueThrows(float value) {
        float[] vector = unit(0);
        vector[7] = value;

        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.upsert("ACME", MODEL, HASH, vector));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void blankArgumentsThrow(String blank) {
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.upsert(blank, MODEL, HASH, unit(0)));
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.upsert("ACME", blank, HASH, unit(0)));
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.upsert("ACME", MODEL, blank, unit(0)));
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.find(blank, MODEL));
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.find("ACME", blank));
        assertThatIllegalArgumentException().isThrownBy(() -> embeddings.inputHashes(blank));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> embeddings.findNearestLabeled(unit(0), blank, "ITEM", -1, 0.5, 40, 5));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> embeddings.findNearestLabeled(unit(0), MODEL, blank, -1, 0.5, 40, 5));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, MerchantEmbeddingRepository.MAX_LIMIT + 1})
    void outOfRangeLimitsThrow(int limit) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> embeddings.findNearestLabeled(unit(0), MODEL, "ITEM", -1, 0.5, limit, 5));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> embeddings.findNearestLabeled(unit(0), MODEL, "ITEM", -1, 0.5, 40, limit));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, MerchantEmbeddingRepository.MAX_LIMIT})
    void boundaryLimitsAreAccepted(int limit) {
        assertThat(embeddings.findNearestLabeled(unit(0), MODEL, "ITEM", -1, 0.5, limit, limit)).isEmpty();
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
