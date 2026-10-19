package com.finalysis.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.categorize.CategorizationService;
import com.finalysis.categorize.CategorizeRows;
import com.finalysis.categorize.MerchantCleaner;
import com.finalysis.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Not a transfer" and the txn's category (ING-05, CAT-08; docs/plans/phase-4.md, step 9d notes). Marking a txn not
 * a transfer clears a RULE or AI category of Transfer kind (never a USER one); Transfer-kind rules and merchant memory
 * skip it, so no later pass brings the category back; it waits in the review queue as
 * {@code NOT_TRANSFER_UNCATEGORIZED}; correcting it makes no rule; undoing the mark restores eligibility only. The AI
 * side (never sent) is in {@code AiCategorizerTest}, example labels in {@code MerchantEmbeddingRepositoryTest}.
 * Synthetic rows in plain SQL; every test rolls back.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class NotTransferCategoryTest {

    /** Generic, as such descriptions are: the seed {@code TRANSFER (TO|FROM) (CHK|...)} matches it. */
    private static final String GENERIC = "ONLINE BANKING TRANSFER TO CHK 4417";
    /** Both the seed Transfer rule and the seed RENT rule match; the Transfer rule's longer pattern wins. */
    private static final String TRANSFER_AND_RENT = "ONLINE TRANSFER TO SAV RENT";

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final CategorizeRows rows;
    private final CategorizationService categorization;
    private long account;

    NotTransferCategoryTest(MockMvc mockMvc, JsonMapper jsonMapper, JdbcTemplate jdbc, MerchantCleaner cleaner,
                            CategorizationService categorization) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.rows = new CategorizeRows(jdbc, cleaner);
        this.categorization = categorization;
    }

    @BeforeEach
    void setUp() {
        account = rows.account("Not Transfer Checking", "CHECKING", "3101");
    }

    // ------------------------------------------------------------------ marking clears a Transfer-kind category

    @Test
    void markingClearsARuleTransferCategoryAndListsTheTxnForTheUser() throws Exception {
        long txn = ruleCategorized(GENERIC, "-500.00");
        assertThat(rows.category(txn)).isEqualTo("Transfer");
        assertThat(kinds()).containsExactly("SUGGESTED_ONE_SIDED_TRANSFER");

        markNotTransfer(txn).andExpect(jsonPath("$.transferExcluded").value(true));

        assertUncategorized(txn);
        JsonNode queue = queue();
        assertThat(kinds(queue)).containsExactly("NOT_TRANSFER_UNCATEGORIZED");
        JsonNode item = queue.get("items").get(0);
        assertThat(item.get("key").asString()).isEqualTo("txn-" + txn);
        assertThat(item.get("txn").get("id").asLong()).isEqualTo(txn);
        assertThat(item.get("txn").get("amount").asString()).isEqualTo("-500.00");
        assertThat(item.get("since").isNull()).isFalse();
        assertThat(queue.get("counts").get("notTransferUncategorized").asInt()).isEqualTo(1);
        assertThat(queue.get("counts").get("suggestedOneSidedTransfers").asInt()).isZero();
    }

    @Test
    void markingClearsAnAiTransferCategoryWithItsProvenance() throws Exception {
        long txn = rows.txn(GENERIC, "-500.00");
        aiCategorize(txn, "Transfer");

        markNotTransfer(txn);

        assertUncategorized(txn);
        assertThat(jdbc.queryForObject("""
                SELECT category_model IS NULL AND category_confidence IS NULL AND category_prompt_version IS NULL
                FROM txn WHERE id = ?""", Boolean.class, txn)).isTrue();
        assertThat(kinds()).containsExactly("NOT_TRANSFER_UNCATEGORIZED");
    }

    @Test
    void aUserCategoryAndAnAiCategoryOfAnotherKindStay() throws Exception {
        long user = rows.txn(GENERIC, "-500.00");
        rows.categorize(user, "Transfer", "USER");
        long ai = rows.txn("HARBOR NOODLE HOUSE", "-18.00");
        aiCategorize(ai, "Dining");

        markNotTransfer(user);
        markNotTransfer(ai);

        assertThat(rows.category(user)).isEqualTo("Transfer");
        assertThat(rows.source(user)).isEqualTo("USER");
        assertThat(rows.category(ai)).isEqualTo("Dining");
        assertThat(rows.source(ai)).isEqualTo("AI");
        assertThat(kinds()).as("both have a category; a USER Transfer marked not a transfer is not suggested").isEmpty();
    }

    @Test
    void aLowerRankedRuleOfAnotherKindStillApplies() throws Exception {
        long txn = ruleCategorized(TRANSFER_AND_RENT, "-1200.00");
        assertThat(rows.category(txn)).as("the Transfer seed wins on an unmarked txn").isEqualTo("Transfer");

        markNotTransfer(txn);

        assertThat(rows.category(txn)).isEqualTo("Housing");
        assertThat(rows.source(txn)).isEqualTo("RULE");
        assertThat(rows.ruleId(txn)).isEqualTo(rows.seed("\\bRENT\\b(?! A CAR)", "OUT"));
        assertThat(kinds()).isEmpty();
    }

    @Test
    void markingRemovesTheLinkAndClearsTheCategoryTogether() throws Exception {
        long txn = ruleCategorized(GENERIC, "-500.00");
        jdbc.update("INSERT INTO transfer_link (out_txn_id, method, status, confirmed_at) "
                + "VALUES (?, 'USER', 'UNMATCHED', now())", txn);

        markNotTransfer(txn).andExpect(jsonPath("$.removedLinkId").isNumber());

        assertThat(jdbc.queryForObject("SELECT is_transfer FROM txn WHERE id = ?", Boolean.class, txn)).isFalse();
        assertUncategorized(txn);
    }

    // ------------------------------------------------------------------ nothing brings it back

    @Test
    void noLaterPassOrMerchantMemoryBringsATransferCategoryBack() throws Exception {
        long txn = ruleCategorized(GENERIC, "-500.00");
        markNotTransfer(txn);
        long twin = rows.txn(GENERIC, "-75.00", "2026-04-20"); // same merchant, an unmarked AI answer to copy
        aiCategorize(twin, "Transfer");

        categorization.categorizeAll();
        categorization.categorizeTxn(txn);
        categorization.categorizeMerchant(jdbc.queryForObject("SELECT merchant FROM txn WHERE id = ?", String.class,
                txn));
        mockMvc.perform(post("/api/categorization/run").param("ai", "false")).andExpect(status().isOk());

        assertUncategorized(txn);
        assertThat(kinds()).contains("NOT_TRANSFER_UNCATEGORIZED");
    }

    /** Data from before the fix: a pass clears a contradictory RULE or AI category on a txn already marked. */
    @Test
    void aPassClearsAContradictionLeftFromBefore() {
        long byRule = ruleCategorized(GENERIC, "-500.00");
        long byAi = rows.txn("ONLINE TRANSFER FROM SAV 8812", "300.00");
        aiCategorize(byAi, "Transfer");
        jdbc.update("UPDATE txn SET transfer_excluded = true WHERE id IN (?, ?)", byRule, byAi);

        CategorizationService.RulesResult result = categorization.categorizeAll();

        assertUncategorized(byRule);
        assertUncategorized(byAi);
        assertThat(result.aiTransferCleared()).isEqualTo(1);
        assertThat(result.ruleCleared()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void anAiReviewMarkIsListedOnceAsNotATransfer() throws Exception {
        long txn = rows.txn(GENERIC, "-500.00");
        jdbc.update("UPDATE txn SET ai_review_reason = 'LOW_CONFIDENCE', ai_review_at = now() WHERE id = ?", txn);
        assertThat(kinds()).containsExactly("AI_CATEGORY_REVIEW");

        markNotTransfer(txn);

        assertThat(kinds()).containsExactly("NOT_TRANSFER_UNCATEGORIZED");
    }

    // ------------------------------------------------------------------ corrections make no rule

    @Test
    void correctingATxnMarkedNotATransferCreatesNoRule() throws Exception {
        long txn = ruleCategorized(GENERIC, "-500.00");
        long realTransfer = ruleCategorized(GENERIC, "-200.00");
        markNotTransfer(txn);

        correct(txn, "{\"categoryId\": " + rows.categoryId("Housing") + "}")
                .andExpect(jsonPath("$.txn.category.name").value("Housing"))
                .andExpect(jsonPath("$.txn.categorySource").value("USER"))
                .andExpect(jsonPath("$.ruleAction").value("NONE"))
                .andExpect(jsonPath("$.ruleId").isEmpty());
        correct(txn, "{\"categoryId\": " + rows.categoryId("Housing") + ", \"applyToMerchant\": true}")
                .andExpect(jsonPath("$.ruleAction").value("NONE"));

        assertThat(userRules()).isZero();
        assertThat(rows.category(realTransfer)).as("the real transfer keeps its rule category").isEqualTo("Transfer");
        assertThat(rows.source(realTransfer)).isEqualTo("RULE");
        assertThat(kinds()).containsExactly("SUGGESTED_ONE_SIDED_TRANSFER"); // the real transfer only
    }

    /** Its correction made no rule, so a full undo deletes none, not even one the merchant got elsewhere. */
    @Test
    void aFullUndoOnATxnMarkedNotATransferDeletesNoRule() throws Exception {
        long txn = ruleCategorized(GENERIC, "-500.00");
        long twin = ruleCategorized(GENERIC, "-200.00");
        correct(twin, "{\"categoryId\": " + rows.categoryId("Mortgage") + "}")
                .andExpect(jsonPath("$.ruleAction").value("CREATED"));
        markNotTransfer(txn);
        correct(txn, "{\"categoryId\": " + rows.categoryId("Housing") + "}");

        mockMvc.perform(delete("/api/transactions/{id}/category", txn).param("removeRule", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleAction").value("NONE"));

        assertThat(userRules()).isEqualTo(1);
        assertThat(rows.source(txn)).as("the twin's correction rule is of another kind, so it applies").isEqualTo("RULE");
        assertThat(rows.category(txn)).isEqualTo("Mortgage");
    }

    // ------------------------------------------------------------------ undo

    @Test
    void undoingRestoresEligibilityOnly() throws Exception {
        long txn = ruleCategorized(GENERIC, "-500.00");
        markNotTransfer(txn);

        mockMvc.perform(delete("/api/transactions/{id}/not-transfer", txn)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT transfer_excluded FROM txn WHERE id = ?", Boolean.class, txn)).isFalse();
        assertUncategorized(txn); // nothing runs now
        assertThat(kinds()).isEmpty();

        categorization.categorizeTxn(txn);

        assertThat(rows.category(txn)).isEqualTo("Transfer");
        assertThat(rows.source(txn)).isEqualTo("RULE");
        assertThat(kinds()).containsExactly("SUGGESTED_ONE_SIDED_TRANSFER");
    }

    // ------------------------------------------------------------------ helpers

    private long ruleCategorized(String description, String amount) {
        long txn = rows.txn(description, amount);
        categorization.categorizeTxn(txn);
        return txn;
    }

    private void aiCategorize(long txn, String category) {
        jdbc.update("""
                UPDATE txn SET category_id = (SELECT id FROM category WHERE name = ?), category_source = 'AI',
                               category_rule_id = NULL, categorized_at = now(), category_model = 'test-model',
                               category_confidence = 0.95, category_prompt_version = '3'
                WHERE id = ?""", category, txn);
    }

    private ResultActions markNotTransfer(long txn) throws Exception {
        return mockMvc.perform(post("/api/transactions/{id}/not-transfer", txn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txnId").value(txn));
    }

    private ResultActions correct(long txn, String body) throws Exception {
        return mockMvc.perform(put("/api/transactions/{id}/category", txn)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private void assertUncategorized(long txn) {
        assertThat(rows.category(txn)).isNull();
        assertThat(rows.source(txn)).isNull();
        assertThat(rows.ruleId(txn)).isNull();
    }

    private int userRules() {
        return jdbc.queryForObject("SELECT count(*) FROM categorization_rule WHERE source <> 'SEED'", Integer.class);
    }

    private JsonNode queue() throws Exception {
        return jsonMapper.readTree(mockMvc.perform(get("/api/review-queue").param("accountId", String.valueOf(account)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<String> kinds() throws Exception {
        return kinds(queue());
    }

    private static List<String> kinds(JsonNode queue) {
        List<String> kinds = new ArrayList<>();
        queue.get("items").forEach(item -> kinds.add(item.get("kind").asString()));
        return kinds;
    }
}
