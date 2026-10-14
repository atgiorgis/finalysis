package com.finalysis.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.categorize.CategorizationService;
import com.finalysis.categorize.CategorizeRows;
import com.finalysis.categorize.MerchantCleaner;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Corrections (CAT-04; docs/plans/phase-4.md §9.2): {@code PUT} and {@code DELETE /api/transactions/{id}/category}.
 * The rule a correction upserts (merchant, exact, the txn's direction), what it changes on the merchant's other
 * txns, that it outranks seeds whatever their priority, undo, and that no later pass overwrites a USER category.
 * Not transactional: imports commit, so each test deletes what it made.
 */
@IntegrationTest
@AutoConfigureMockMvc
class CorrectionTest {

    private static final String PROBLEM = "urn:finalysis:problem:";

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final CategorizeRows rows;
    private final ImportTestSupport support;
    private final CategorizationService categorization;

    CorrectionTest(MockMvc mockMvc, JsonMapper jsonMapper, JdbcTemplate jdbc, MerchantCleaner cleaner,
                   AccountRepository accounts, CategorizationService categorization) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.rows = new CategorizeRows(jdbc, cleaner);
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.categorization = categorization;
    }

    @AfterEach
    void cleanUp() {
        support.deleteCreatedAccounts();
        rows.deleteAccounts();
        jdbc.update("DELETE FROM categorization_rule WHERE source <> 'SEED'");
        jdbc.update("UPDATE categorization_rule SET active = true WHERE source = 'SEED' AND NOT active");
    }

    private ResultActions correct(long txnId, String category) throws Exception {
        return correctWith(txnId, "{\"categoryId\": " + rows.categoryId(category) + "}");
    }

    private ResultActions correct(long txnId, String category, boolean applyToMerchant) throws Exception {
        return correctWith(txnId, "{\"categoryId\": " + rows.categoryId(category) + ", \"applyToMerchant\": "
                + applyToMerchant + "}");
    }

    private ResultActions correctWith(long txnId, String json) throws Exception {
        return mockMvc.perform(put("/api/transactions/{id}/category", txnId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<Map<String, Object>> userRules() {
        return jdbc.queryForList("""
                SELECT r.id, r.field, r.match_type, r.pattern, r.direction, r.priority, r.source, r.active, c.name
                FROM categorization_rule r JOIN category c ON c.id = r.category_id
                WHERE r.source <> 'SEED' ORDER BY r.id""");
    }

    // ------------------------------------------------------------------ the corrected txn and its rule

    @Test
    void aCorrectionSetsUserAndUpsertsOneExactMerchantRuleInTheTxnsDirection() throws Exception {
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        categorization.categorizeAll();
        assertThat(rows.source(cafe)).isEqualTo("RULE");

        JsonNode body = json(correct(cafe, "Groceries"));

        assertThat(body.get("txn").get("id").asLong()).isEqualTo(cafe);
        assertThat(body.get("txn").get("category").get("name").asString()).isEqualTo("Groceries");
        assertThat(body.get("txn").get("categorySource").asString()).isEqualTo("USER");
        assertThat(body.get("txn").get("ruleId").isNull()).as("a USER category names no rule").isTrue();
        assertThat(body.get("txn").get("categorizedAt").isNull()).isFalse();
        assertThat(body.get("txn").get("amount").asString()).isEqualTo("-6.25");
        assertThat(body.get("ruleAction").asString()).isEqualTo("CREATED");
        List<Map<String, Object>> rules = userRules();
        assertThat(rules).hasSize(1);
        assertThat(rules.getFirst()).containsEntry("id", body.get("ruleId").asLong())
                .containsEntry("field", "MERCHANT").containsEntry("match_type", "EXACT")
                .containsEntry("pattern", "BLUE BEAN CAFE").containsEntry("direction", "OUT")
                .containsEntry("priority", 0).containsEntry("source", "USER_CORRECTION")
                .containsEntry("active", true).containsEntry("name", "Groceries");

        JsonNode again = json(correct(cafe, "Shopping"));
        assertThat(again.get("ruleId").asLong()).as("correcting twice updates the one rule")
                .isEqualTo(body.get("ruleId").asLong());
        assertThat(again.get("ruleAction").asString()).isEqualTo("UPDATED");
        assertThat(userRules()).singleElement().satisfies(rule -> assertThat(rule).containsEntry("name", "Shopping"));

        JsonNode same = json(correct(cafe, "Shopping"));
        assertThat(same.get("ruleAction").asString()).isEqualTo("UNCHANGED");
        assertThat(userRules()).hasSize(1);
    }

    @Test
    void moneyInGetsAnInRuleAndAZeroAmountAnAnyRule() throws Exception {
        long refund = rows.txn("BLUE BEAN CAFE", "6.25");
        long zero = rows.txn("BLUE BEAN CAFE", "0.00");

        correct(refund, "Refunds & Rebates").andExpect(status().isOk()).andExpect(jsonPath("$.ruleAction").value("CREATED"));
        correct(zero, "Dining").andExpect(status().isOk()).andExpect(jsonPath("$.ruleAction").value("CREATED"));

        assertThat(userRules()).extracting(rule -> rule.get("direction")).containsExactly("IN", "ANY");
    }

    @Test
    void anExistingApiRuleWithTheSameKeyIsUpdatedNotDuplicated() throws Exception {
        long cafe = rows.txn("BLUE BEAN CAFE", "-6.25");
        String created = mockMvc.perform(post("/api/categorization/rules").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"matchType": "EXACT", "pattern": "BLUE BEAN CAFE", "direction": "OUT", "categoryId": %d}"""
                                .formatted(rows.categoryId("Dining"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long apiRule = jsonMapper.readTree(created).get("rule").get("id").asLong();

        JsonNode body = json(correct(cafe, "Shopping"));

        assertThat(body.get("ruleId").asLong()).isEqualTo(apiRule);
        assertThat(body.get("ruleAction").asString()).isEqualTo("UPDATED");
        assertThat(userRules()).singleElement().satisfies(rule -> assertThat(rule)
                .containsEntry("source", "USER").containsEntry("name", "Shopping"));
    }

    @Test
    void withoutApplyToMerchantOrAMerchantOnlyTheTxnChanges() throws Exception {
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        long other = rows.txn("SQ *BLUE BEAN CAFE 4412", "-3.10");
        long blank = rows.txn("#12345 0099", "-9.00");
        categorization.categorizeAll();
        assertThat(rows.ruleId(blank)).as("no merchant").isNull();

        correct(cafe, "Groceries", false).andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").isEmpty())
                .andExpect(jsonPath("$.ruleAction").value("NONE"))
                .andExpect(jsonPath("$.otherTxnsUpdated").value(0));
        correct(blank, "Shopping").andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleAction").value("NONE"))
                .andExpect(jsonPath("$.txn.categorySource").value("USER"));

        assertThat(userRules()).isEmpty();
        assertThat(rows.category(other)).isEqualTo("Dining");
        assertThat(rows.source(other)).isEqualTo("RULE");
    }

    // ------------------------------------------------------------------ other txns

    @Test
    void theMerchantsOtherTxnsFollowExceptUserOnesAndTheOtherDirection() throws Exception {
        rows.account("Correction Checking", "CHECKING", "7201");
        long corrected = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25", "2026-04-02");
        long ruled = rows.txn("SQ *BLUE BEAN CAFE 4412", "-4.75", "2026-04-09");
        long ai = rows.txn("BLUE BEAN CAFE", "-5.10", "2026-04-16");
        long user = rows.txn("BLUE BEAN CAFE", "-7.00", "2026-04-23");
        long refund = rows.txn("BLUE BEAN CAFE", "6.25", "2026-04-24");
        rows.account("Correction Card", "CREDIT_CARD", "7202");
        long onCard = rows.txn("TST* BLUE BEAN CAFE", "-8.40", "2026-05-01");
        long otherMerchant = rows.txn("SQ *NAKAMURA RAMEN", "-14.00", "2026-05-02");
        categorization.categorizeAll();
        rows.categorize(ai, "Dining", "AI");
        rows.categorize(user, "Health", "USER");

        JsonNode body = json(correct(corrected, "Groceries"));

        long ruleId = body.get("ruleId").asLong();
        assertThat(body.get("otherTxnsUpdated").asInt()).as("ruled, ai, on the card").isEqualTo(3);
        assertThat(body.get("otherTxnsKeptUser").asInt()).isEqualTo(1);
        assertThat(body.get("otherTxnsKeptOtherRule").asInt()).isZero();
        for (long txn : List.of(ruled, ai, onCard)) {
            assertThat(rows.category(txn)).isEqualTo("Groceries");
            assertThat(rows.source(txn)).isEqualTo("RULE");
            assertThat(rows.ruleId(txn)).isEqualTo(ruleId);
        }
        assertThat(rows.category(user)).as("USER kept").isEqualTo("Health");
        assertThat(rows.source(user)).isEqualTo("USER");
        assertThat(rows.category(refund)).as("money in isn't matched by an OUT rule").isNotEqualTo("Groceries");
        assertThat(rows.category(otherMerchant)).isEqualTo("Dining");
    }

    @Test
    void aCorrectionOutranksASeedWithAHigherPriority() throws Exception {
        long zelle = rows.txn("ZELLE PAYMENT TO JANE Q SAMPLE", "-40.00");
        long another = rows.txn("ZELLE PAYMENT TO PAT M TESTER", "-25.00");
        categorization.categorizeAll();
        long seed = rows.seed("ZELLE", "OUT");
        assertThat(rows.ruleId(another)).isEqualTo(seed);
        assertThat(jdbc.queryForObject("SELECT priority FROM categorization_rule WHERE id = ?", Integer.class, seed))
                .isEqualTo(100);

        JsonNode body = json(correct(zelle, "Housing"));

        assertThat(body.get("otherTxnsUpdated").asInt()).isEqualTo(1);
        assertThat(rows.category(another)).isEqualTo("Housing");
        assertThat(rows.ruleId(another)).isEqualTo(body.get("ruleId").asLong());
        categorization.run(false);
        assertThat(rows.ruleId(another)).as("still after a full run").isEqualTo(body.get("ruleId").asLong());
    }

    @Test
    void aHigherRankedUserRuleStillWinsAndIsReported() throws Exception {
        long cafe = rows.txn("BLUE BEAN CAFE", "-6.25");
        long other = rows.txn("BLUE BEAN CAFE", "-4.00");
        mockMvc.perform(post("/api/categorization/rules").contentType(MediaType.APPLICATION_JSON).content("""
                {"matchType": "CONTAINS", "pattern": "BEAN", "priority": 500, "categoryId": %d}"""
                .formatted(rows.categoryId("Shopping")))).andExpect(status().isCreated());

        JsonNode body = json(correct(cafe, "Groceries"));

        assertThat(body.get("otherTxnsUpdated").asInt()).isZero();
        assertThat(body.get("otherTxnsKeptOtherRule").asInt()).isEqualTo(1);
        assertThat(rows.category(other)).isEqualTo("Shopping");
        assertThat(rows.category(cafe)).isEqualTo("Groceries");
    }

    @Test
    void otherTxnsAcrossImportedStatementsFollow() throws Exception {
        long account = support.account(AccountType.CHECKING, "1001");
        for (String month : List.of("04", "05", "06")) {
            support.upload(account, month + ".ofx", ImportTestSupport.sample("ofx/northfield-checking_2026-" + month + ".ofx"))
                    .andExpect(status().isCreated());
        }
        Map<String, Object> busiest = jdbc.queryForMap("""
                SELECT merchant, count(*) AS n, min(id) AS first FROM txn
                WHERE account_id = ? AND amount < 0 AND merchant IS NOT NULL AND NOT is_transfer
                GROUP BY merchant ORDER BY count(*) DESC, merchant LIMIT 1""", account);
        long count = (Long) busiest.get("n");
        assertThat(count).as("a merchant that recurs in the sample months").isGreaterThan(1);

        JsonNode body = json(correct((Long) busiest.get("first"), "Tolls"));

        assertThat(body.get("otherTxnsUpdated").asInt()).isEqualTo((int) count - 1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM txn t JOIN category c ON c.id = t.category_id
                WHERE t.account_id = ? AND t.merchant = ? AND t.amount < 0 AND c.name = 'Tolls'""",
                Long.class, account, busiest.get("merchant"))).isEqualTo(count);
    }

    // ------------------------------------------------------------------ USER is never overwritten

    @Test
    void noLaterPassOverwritesAUserCategory() throws Exception {
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        long aiTwin = rows.txn("SQ *BLUE BEAN CAFE 4412", "-3.00");
        categorization.categorizeAll();
        correct(cafe, "Health", false).andExpect(status().isOk());

        // memory: an uncategorized-then-AI twin of the same merchant must not reach the USER txn
        rows.categorize(aiTwin, "Shopping", "AI");
        long dining = rows.seed("CAFE", "OUT");
        mockMvc.perform(patch("/api/categorization/rules/{id}", dining).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\": false}")).andExpect(status().isOk());
        mockMvc.perform(patch("/api/categorization/rules/{id}", dining).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\": true}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/categorization/rules").contentType(MediaType.APPLICATION_JSON).content("""
                {"matchType": "EXACT", "pattern": "BLUE BEAN CAFE", "priority": 1000, "categoryId": %d}"""
                .formatted(rows.categoryId("Tolls")))).andExpect(status().isCreated());
        categorization.run(true);
        categorization.categorizeStatement(rows.statementId());
        correct(aiTwin, "Shopping").andExpect(status().isOk()); // a merchant-wide correction for the same merchant

        assertThat(rows.category(cafe)).isEqualTo("Health");
        assertThat(rows.source(cafe)).isEqualTo("USER");
    }

    // ------------------------------------------------------------------ undo

    @Test
    void clearingAUserCategoryRunsTheRulesForTheTxn() throws Exception {
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        long other = rows.txn("SQ *BLUE BEAN CAFE 4412", "-3.00");
        categorization.categorizeAll();
        long seed = rows.ruleId(cafe);
        long ruleId = json(correct(cafe, "Groceries")).get("ruleId").asLong();

        mockMvc.perform(delete("/api/transactions/{id}/category", cafe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleAction").value("NONE")) // the same body as a full undo, no rule touched
                .andExpect(jsonPath("$.ruleId").isEmpty())
                .andExpect(jsonPath("$.otherTxnsUpdated").value(0))
                .andExpect(jsonPath("$.txn.id").value(cafe))
                .andExpect(jsonPath("$.txn.categorySource").value("RULE"))
                .andExpect(jsonPath("$.txn.ruleId").value(ruleId))
                .andExpect(jsonPath("$.txn.category.name").value("Groceries"));
        assertThat(userRules()).hasSize(1);

        // Full undo: delete the correction rule too; both txns go back to the seed.
        mockMvc.perform(delete("/api/categorization/rules/{id}", ruleId)).andExpect(status().isNoContent());
        for (long txn : List.of(cafe, other)) {
            assertThat(rows.category(txn)).isEqualTo("Dining");
            assertThat(rows.ruleId(txn)).isEqualTo(seed);
        }
    }

    @Test
    void clearingWithoutARuleLeavesTheTxnUncategorizedOrToTheSeeds() throws Exception {
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        long unknown = rows.txn("ZZYZX QUILLWORKS 4471", "-12.00");
        categorization.categorizeAll();
        correct(cafe, "Groceries", false).andExpect(status().isOk());
        correct(unknown, "Shopping", false).andExpect(status().isOk());

        mockMvc.perform(delete("/api/transactions/{id}/category", cafe))
                .andExpect(jsonPath("$.txn.categorySource").value("RULE"))
                .andExpect(jsonPath("$.txn.category.name").value("Dining"));
        mockMvc.perform(delete("/api/transactions/{id}/category", unknown))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleAction").value("NONE"))
                .andExpect(jsonPath("$.txn.category").isEmpty())
                .andExpect(jsonPath("$.txn.categorySource").isEmpty())
                .andExpect(jsonPath("$.txn.categorizedAt").isEmpty());
    }

    @Test
    void clearingANonUserCategoryChangesNothing() throws Exception {
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        long ai = rows.txn("ZZYZX QUILLWORKS 4471", "-12.00");
        categorization.categorizeAll();
        rows.categorize(ai, "Shopping", "AI");
        Object before = jdbc.queryForObject("SELECT categorized_at FROM txn WHERE id = ?", Object.class, cafe);

        mockMvc.perform(delete("/api/transactions/{id}/category", cafe))
                .andExpect(status().isOk()).andExpect(jsonPath("$.txn.categorySource").value("RULE"));
        mockMvc.perform(delete("/api/transactions/{id}/category", ai))
                .andExpect(status().isOk()).andExpect(jsonPath("$.txn.categorySource").value("AI"))
                .andExpect(jsonPath("$.ruleAction").value("NONE"));

        assertThat(jdbc.queryForObject("SELECT categorized_at FROM txn WHERE id = ?", Object.class, cafe))
                .isEqualTo(before);
    }

    // ------------------------------------------------------------------ full undo (?removeRule=true)

    @Test
    void aFullUndoDeletesTheCorrectionRuleAndTheMerchantGoesBackToTheSeeds() throws Exception {
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        long other = rows.txn("SQ *BLUE BEAN CAFE 4412", "-3.00");
        long kept = rows.txn("SQ *BLUE BEAN CAFE 4412", "-4.50");
        long refund = rows.txn("SQ *BLUE BEAN CAFE 4412", "6.25");
        categorization.categorizeAll();
        long seed = rows.ruleId(cafe);
        String refundCategory = rows.category(refund);
        rows.categorize(kept, "Shopping", "USER");
        long ruleId = json(correct(cafe, "Groceries")).get("ruleId").asLong();
        assertThat(rows.category(other)).isEqualTo("Groceries");

        JsonNode body = json(mockMvc.perform(delete("/api/transactions/{id}/category", cafe).param("removeRule", "true"))
                .andExpect(status().isOk()));

        assertThat(body.get("ruleAction").asString()).isEqualTo("REMOVED");
        assertThat(body.get("ruleId").asLong()).isEqualTo(ruleId);
        assertThat(body.get("otherTxnsUpdated").asInt()).as("only the other OUT txn; USER and IN unchanged").isOne();
        assertThat(body.get("txn").get("id").asLong()).isEqualTo(cafe);
        assertThat(body.get("txn").get("categorySource").asString()).isEqualTo("RULE");
        assertThat(body.get("txn").get("ruleId").asLong()).isEqualTo(seed);
        assertThat(body.get("txn").get("category").get("name").asString()).isEqualTo("Dining");
        assertThat(userRules()).isEmpty();
        assertThat(rows.category(other)).isEqualTo("Dining");
        assertThat(rows.ruleId(other)).isEqualTo(seed);
        assertThat(rows.source(kept)).isEqualTo("USER");
        assertThat(rows.category(refund)).isEqualTo(refundCategory);
    }

    /** The default undo (here {@code removeRule=false}) leaves the rule; a full undo afterwards deletes it. */
    @Test
    void aFullUndoFinishesATwoCallUndo() throws Exception {
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        categorization.categorizeAll();
        long ruleId = json(correct(cafe, "Groceries")).get("ruleId").asLong();
        mockMvc.perform(delete("/api/transactions/{id}/category", cafe).param("removeRule", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleAction").value("NONE"))
                .andExpect(jsonPath("$.ruleId").isEmpty())
                .andExpect(jsonPath("$.otherTxnsUpdated").value(0))
                .andExpect(jsonPath("$.txn.ruleId").value(ruleId));

        mockMvc.perform(delete("/api/transactions/{id}/category", cafe).param("removeRule", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleAction").value("REMOVED"))
                .andExpect(jsonPath("$.ruleId").value(ruleId))
                .andExpect(jsonPath("$.txn.category.name").value("Dining"));
        assertThat(userRules()).isEmpty();
    }

    /** A rule made through the rules API with the same key is the user's own; a full undo keeps it. */
    @Test
    void aFullUndoKeepsARuleMadeThroughTheRulesApi() throws Exception {
        long cafe = rows.txn("BLUE BEAN CAFE", "-6.25");
        long other = rows.txn("BLUE BEAN CAFE", "-3.00");
        String created = mockMvc.perform(post("/api/categorization/rules").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"matchType": "EXACT", "pattern": "BLUE BEAN CAFE", "direction": "OUT", "categoryId": %d}"""
                                .formatted(rows.categoryId("Dining"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long apiRule = jsonMapper.readTree(created).get("rule").get("id").asLong();
        correct(cafe, "Shopping").andExpect(jsonPath("$.ruleId").value(apiRule));

        mockMvc.perform(delete("/api/transactions/{id}/category", cafe).param("removeRule", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleAction").value("NONE"))
                .andExpect(jsonPath("$.ruleId").isEmpty())
                .andExpect(jsonPath("$.otherTxnsUpdated").value(0))
                .andExpect(jsonPath("$.txn.categorySource").value("RULE"))
                .andExpect(jsonPath("$.txn.ruleId").value(apiRule))
                .andExpect(jsonPath("$.txn.category.name").value("Shopping"));
        assertThat(userRules()).singleElement().satisfies(rule -> assertThat(rule)
                .containsEntry("id", apiRule).containsEntry("source", "USER"));
        assertThat(rows.ruleId(other)).isEqualTo(apiRule);
    }

    /** Without a correction rule a full undo is a plain clear: seeds are never deleted. */
    @Test
    void aFullUndoWithoutACorrectionRuleDeletesNothing() throws Exception {
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        categorization.categorizeAll();
        long seed = rows.ruleId(cafe);
        correct(cafe, "Groceries", false).andExpect(status().isOk());
        int rulesBefore = support.count("SELECT count(*) FROM categorization_rule");

        mockMvc.perform(delete("/api/transactions/{id}/category", cafe).param("removeRule", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleAction").value("NONE"))
                .andExpect(jsonPath("$.ruleId").isEmpty())
                .andExpect(jsonPath("$.txn.ruleId").value(seed));
        assertThat(support.count("SELECT count(*) FROM categorization_rule")).isEqualTo(rulesBefore);
        mockMvc.perform(delete("/api/transactions/{id}/category", 999_999_999).param("removeRule", "true"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/transactions/{id}/category", cafe).param("removeRule", "maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("removeRule"));
    }

    // ------------------------------------------------------------------ errors

    @Test
    void errors() throws Exception {
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        categorization.categorizeAll();

        correct(999_999_999, "Groceries").andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/transactions/{id}/category", 999_999_999)).andExpect(status().isNotFound());
        correctWith(cafe, "{\"categoryId\": 999999999}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEM + "category-invalid"));
        correctWith(cafe, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));
        correctWith(cafe, "{\"categoryId\": 1, \"merchant\": \"X\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("merchant"));

        assertThat(rows.source(cafe)).as("nothing changed").isEqualTo("RULE");
        assertThat(userRules()).isEmpty();
    }
}
