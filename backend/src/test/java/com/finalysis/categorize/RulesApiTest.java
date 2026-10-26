package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.categorize.RuleEngine.Rule;
import com.finalysis.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
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
 * The rules API and the on-demand run (docs/plans/phase-4.md §3.2, §9.2): every endpoint, its errors, and that a
 * change applies at once.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional // each test's rules, txns and categories roll back
class RulesApiTest {

    private static final String RULES = "/api/categorization/rules";
    private static final String PROBLEM = "urn:finalysis:problem:";

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final CategorizeRows rows;

    RulesApiTest(MockMvc mockMvc, JsonMapper jsonMapper, JdbcTemplate jdbc, MerchantCleaner cleaner) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.rows = new CategorizeRows(jdbc, cleaner);
    }

    private ResultActions create(String json) throws Exception {
        return mockMvc.perform(post(RULES).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions update(long id, String json) throws Exception {
        return mockMvc.perform(patch(RULES + "/{id}", id).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long createdId(ResultActions result) throws Exception {
        return json(result.andExpect(status().isCreated())).get("rule").get("id").asLong();
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String rule(String matchType, String pattern, String category) {
        return """
                {"matchType": "%s", "pattern": "%s", "categoryId": %d}""".formatted(matchType, pattern, rows.categoryId(category));
    }

    // ------------------------------------------------------------------ list and get

    @Test
    void listsRulesInWinningOrder() throws Exception {
        createdId(create(rule("CONTAINS", "ZZ TEST LOWEST", "Shopping")));

        JsonNode list = json(mockMvc.perform(get(RULES)).andExpect(status().isOk()));

        assertThat(list.get(0).get("pattern").asString()).as("user rules outrank seeds").isEqualTo("ZZ TEST LOWEST");
        List<Rule> listed = new ArrayList<>();
        for (JsonNode rule : list) {
            listed.add(new Rule(rule.get("id").asLong(), RuleField.valueOf(rule.get("field").asString()),
                    RuleMatchType.valueOf(rule.get("matchType").asString()), rule.get("pattern").asString(),
                    RuleDirection.valueOf(rule.get("direction").asString()), rule.get("priority").asInt(),
                    RuleSource.valueOf(rule.get("source").asString()), rule.get("category").get("id").asLong()));
        }
        assertThat(listed).isSortedAccordingTo(RuleEngine.ORDER).hasSizeGreaterThan(60);
    }

    @Test
    void filtersBySourceActiveAndCategory() throws Exception {
        long mine = createdId(create(rule("CONTAINS", "ZZ TEST", "Tolls")));
        long tolls = rows.categoryId("Tolls");

        mockMvc.perform(get(RULES).param("source", "USER"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(mine));
        mockMvc.perform(get(RULES).param("source", "SEED"))
                .andExpect(jsonPath("$[*].source", everyItem(is("SEED"))));
        mockMvc.perform(get(RULES).param("active", "false")).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(RULES).param("categoryId", String.valueOf(tolls)))
                .andExpect(jsonPath("$[*].category.name", everyItem(is("Tolls"))))
                .andExpect(jsonPath("$[0].id").value(mine));
        mockMvc.perform(get(RULES).param("source", "MAGIC")).andExpect(status().isBadRequest());
    }

    @Test
    void getsOneRule() throws Exception {
        long seed = rows.seed("ZELLE", "OUT");

        mockMvc.perform(get(RULES + "/{id}", seed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(seed))
                .andExpect(jsonPath("$.field").value("MERCHANT"))
                .andExpect(jsonPath("$.matchType").value("EXACT"))
                .andExpect(jsonPath("$.pattern").value("ZELLE"))
                .andExpect(jsonPath("$.direction").value("OUT"))
                .andExpect(jsonPath("$.priority").value(100))
                .andExpect(jsonPath("$.source").value("SEED"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.category.name").value("Person-to-Person Out"))
                .andExpect(jsonPath("$.category.kind").value("EXPENSE"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
        mockMvc.perform(get(RULES + "/{id}", 999_999_999)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ create

    @Test
    void createsAUserRuleNormalizedWithDefaultsAndAppliesItAtOnce() throws Exception {
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        long refund = rows.txn("BLUE BEAN CAFE REFUND", "6.25");
        long other = rows.txn("TST* NAKAMURA RAMEN", "-14.00");

        ResultActions result = create("""
                {"matchType": "CONTAINS", "pattern": "  blue   bean café ", "categoryId": %d}"""
                .formatted(rows.categoryId("Shopping")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rule.pattern").value("BLUE BEAN CAFE"))
                .andExpect(jsonPath("$.rule.field").value("MERCHANT"))
                .andExpect(jsonPath("$.rule.direction").value("ANY"))
                .andExpect(jsonPath("$.rule.priority").value(0))
                .andExpect(jsonPath("$.rule.source").value("USER"))
                .andExpect(jsonPath("$.rule.active").value(true))
                .andExpect(jsonPath("$.rule.category.name").value("Shopping"))
                .andExpect(jsonPath("$.matchCount").value(2))
                .andExpect(jsonPath("$.winCount").value(2))
                .andExpect(jsonPath("$.shadowedBy").value(nullValue()));
        long id = json(result).get("rule").get("id").asLong();

        assertThat(rows.category(cafe)).isEqualTo("Shopping");
        assertThat(rows.ruleId(cafe)).isEqualTo(id);
        assertThat(rows.category(refund)).isEqualTo("Shopping");
        assertThat(rows.category(other)).isEqualTo("Dining");
    }

    @Test
    void theLocationNamesTheNewRule() throws Exception {
        ResultActions result = create(rule("EXACT", "ZZ TEST", "Tolls"));
        long id = createdId(result);

        result.andExpect(header().string("Location", endsWith(RULES + "/" + id)));
    }

    @Test
    void aRuleThatNeverWinsNamesTheRuleThatShadowsIt() throws Exception {
        rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        rows.txn("BLUE BEAN CAFE ANNEX", "-3.00");
        long winner = createdId(create("""
                {"matchType": "CONTAINS", "pattern": "BLUE BEAN", "priority": 10, "categoryId": %d}"""
                .formatted(rows.categoryId("Groceries"))));

        create(rule("CONTAINS", "BLUE BEAN CAFE", "Shopping"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.matchCount").value(2))
                .andExpect(jsonPath("$.winCount").value(0))
                .andExpect(jsonPath("$.shadowedBy").value(winner));
    }

    @Test
    void aRuleThatMatchesNothingHasZeroCountsAndNoShadow() throws Exception {
        create(rule("EXACT", "ZZ NOTHING MATCHES THIS", "Tolls"))
                .andExpect(jsonPath("$.matchCount").value(0))
                .andExpect(jsonPath("$.winCount").value(0))
                .andExpect(jsonPath("$.shadowedBy").value(nullValue()));
    }

    @Test
    void aRegexKeepsItsCaseAndMatchesCaseInsensitively() throws Exception {
        long txn = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");

        create("""
                {"field": "DESCRIPTION", "matchType": "REGEX", "pattern": "^sq \\\\*blue\\\\b", "direction": "OUT",
                 "categoryId": %d}""".formatted(rows.categoryId("Shopping")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rule.pattern").value("^sq \\*blue\\b"))
                .andExpect(jsonPath("$.rule.field").value("DESCRIPTION"))
                .andExpect(jsonPath("$.winCount").value(1));

        assertThat(rows.category(txn)).isEqualTo("Shopping");
    }

    @Test
    void missingOrOutOfRangeFieldsAre400() throws Exception {
        create("""
                {"matchType": "CONTAINS", "categoryId": 1}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("pattern"));
        create("""
                {"matchType": "CONTAINS", "pattern": "X", "priority": 1001, "categoryId": 1}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("priority"));
        create("""
                {"matchType": "SOUNDEX", "pattern": "X", "categoryId": 1}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("matchType"));
        create("""
                {"matchType": "CONTAINS", "pattern": "X", "categoryId": 1, "source": "SEED"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("source"));
    }

    @Test
    void anUnusablePatternIs422RuleInvalidAndNeverEchoed() throws Exception {
        for (String json : List.of(
                rule("REGEX", "([SECRET-PATTERN", "Shopping"),
                rule("CONTAINS", "   ", "Shopping"),
                rule("CONTAINS", "SECRET-PATTERN".repeat(15), "Shopping"))) {
            String body = create(json)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type").value(PROBLEM + "rule-invalid"))
                    .andExpect(jsonPath("$.field").value("pattern"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("SECRET");
        }
    }

    @Test
    void anUnknownCategoryIs422CategoryInvalid() throws Exception {
        create("""
                {"matchType": "CONTAINS", "pattern": "X", "categoryId": 999999999}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEM + "category-invalid"));
    }

    @Test
    void aDuplicateActiveUserRuleIs409RuleExists() throws Exception {
        long first = createdId(create(rule("CONTAINS", "ZZ TEST", "Tolls")));

        create(rule("CONTAINS", "zz test", "Shopping"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "rule-exists"))
                .andExpect(jsonPath("$.ruleId").value(first));
        createdId(create(rule("PREFIX", "ZZ TEST", "Shopping"))); // another match type is another rule

        update(first, """
                {"active": false}""").andExpect(status().isOk());
        long second = createdId(create(rule("CONTAINS", "ZZ TEST", "Shopping")));

        update(first, """
                {"active": true}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ruleId").value(second));
    }

    @Test
    void aSeedPatternCanAlsoBeAUserRule() throws Exception {
        createdId(create("""
                {"matchType": "EXACT", "pattern": "ZELLE", "direction": "OUT", "categoryId": %d}"""
                .formatted(rows.categoryId("Transfer"))));
    }

    // ------------------------------------------------------------------ update

    @Test
    void aUserRuleCanChangeEveryFieldAndTheChangeAppliesAtOnce() throws Exception {
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        long id = createdId(create(rule("EXACT", "BLUE BEAN CAFE", "Shopping")));
        assertThat(rows.category(cafe)).isEqualTo("Shopping");

        update(id, """
                {"field": "DESCRIPTION", "matchType": "REGEX", "pattern": "bean", "direction": "OUT", "priority": 7,
                 "categoryId": %d}""".formatted(rows.categoryId("Groceries")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rule.field").value("DESCRIPTION"))
                .andExpect(jsonPath("$.rule.matchType").value("REGEX"))
                .andExpect(jsonPath("$.rule.pattern").value("bean"))
                .andExpect(jsonPath("$.rule.direction").value("OUT"))
                .andExpect(jsonPath("$.rule.priority").value(7))
                .andExpect(jsonPath("$.rule.category.name").value("Groceries"))
                .andExpect(jsonPath("$.winCount").value(1));
        assertThat(rows.category(cafe)).isEqualTo("Groceries");

        update(id, """
                {"direction": "IN"}""").andExpect(status().isOk());
        assertThat(rows.category(cafe)).as("the seed CAFE takes it").isEqualTo("Dining");

        update(id, """
                {"matchType": "CONTAINS"}""")
                .andExpect(jsonPath("$.rule.pattern").value("BEAN")); // re-normalized for its new type
    }

    @Test
    void anEmptyPatchChangesNothing() throws Exception {
        long id = createdId(create(rule("EXACT", "ZZ TEST", "Tolls")));

        update(id, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.rule.pattern").value("ZZ TEST"));
    }

    @Test
    void aSeedCanOnlyBeDeactivatedAndReactivated() throws Exception {
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        long seed = rows.seed("CAFE", "OUT");

        update(seed, """
                {"active": false}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rule.active").value(false))
                .andExpect(jsonPath("$.matchCount").value(1));
        assertThat(rows.category(cafe)).as("its txns are cleared").isNull();

        update(seed, """
                {"active": true}""").andExpect(status().isOk());
        assertThat(rows.category(cafe)).isEqualTo("Dining");

        for (String json : List.of("""
                {"pattern": "COFFEE"}""", """
                {"active": false, "priority": 5}""")) {
            update(seed, json)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value(PROBLEM + "rule-seeded"));
        }
        mockMvc.perform(get(RULES + "/{id}", seed)).andExpect(jsonPath("$.pattern").value("CAFE"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void updateErrors() throws Exception {
        long id = createdId(create(rule("EXACT", "ZZ TEST", "Tolls")));

        update(999_999_999, "{}").andExpect(status().isNotFound());
        update(id, """
                {"matchType": "REGEX", "pattern": "("}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEM + "rule-invalid"));
        update(id, """
                {"categoryId": 999999999}""")
                .andExpect(jsonPath("$.type").value(PROBLEM + "category-invalid"));
        update(id, """
                {"priority": -1}""").andExpect(status().isBadRequest());
        update(id, """
                {"source": "SEED"}""").andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ delete

    @Test
    void deletingAUserRuleReEvaluatesItsTxns() throws Exception {
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        long id = createdId(create(rule("EXACT", "BLUE BEAN CAFE", "Shopping")));

        mockMvc.perform(delete(RULES + "/{id}", id)).andExpect(status().isNoContent());

        mockMvc.perform(get(RULES + "/{id}", id)).andExpect(status().isNotFound());
        assertThat(rows.category(cafe)).isEqualTo("Dining");
        assertThat(rows.ruleId(cafe)).isEqualTo(rows.seed("CAFE", "OUT"));
    }

    @Test
    void aSeedCantBeDeleted() throws Exception {
        long seed = rows.seed("CAFE", "OUT");

        mockMvc.perform(delete(RULES + "/{id}", seed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "rule-seeded"));
        mockMvc.perform(get(RULES + "/{id}", seed)).andExpect(status().isOk());
        mockMvc.perform(delete(RULES + "/{id}", 999_999_999)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ run

    @Test
    void runReturnsCountsOnly() throws Exception {
        long txn = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");

        mockMvc.perform(post("/api/categorization/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCategorized").value(1))
                .andExpect(jsonPath("$.merchantsCleaned").isNumber())
                .andExpect(jsonPath("$.ruleCleared").isNumber())
                .andExpect(jsonPath("$.memoryApplied").isNumber())
                .andExpect(jsonPath("$.ai.status").value("OFF"))
                .andExpect(jsonPath("$.ai.calls").value(0))
                .andExpect(jsonPath("$.searchIndex.status").value("OFF")) // step 7e
                .andExpect(jsonPath("$.summary.categorized").value(1))
                .andExpect(jsonPath("$.summary.toReview").isNumber())
                .andExpect(jsonPath("$.summary.length()").value(5))
                .andExpect(jsonPath("$.length()").value(7));
        assertThat(rows.category(txn)).isEqualTo("Dining");

        mockMvc.perform(post("/api/categorization/run").param("reclean", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCategorized").value(0));
        mockMvc.perform(get("/api/categorization/run")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/api/categorization/run").param("reclean", "maybe")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/categorization/run").param("force", "maybe")).andExpect(status().isBadRequest());
    }

    @Test
    void theRunEndpointNeverOverwritesAUserCategory() throws Exception {
        long txn = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        rows.categorize(txn, "Shopping", "USER");

        mockMvc.perform(post("/api/categorization/run").param("reclean", "true")).andExpect(status().isOk());

        assertThat(rows.category(txn)).isEqualTo("Shopping");
        assertThat(rows.source(txn)).isEqualTo("USER");
        assertThat(rows.ruleId(txn)).isNull();
    }

    @Test
    void responsesNeverIncludeTxnText() throws Exception {
        rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");

        String body = create(rule("CONTAINS", "BEAN", "Shopping")).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"BEAN\"").doesNotContain("4412").doesNotContain("6.25").doesNotContain("BLUE");
    }
}
