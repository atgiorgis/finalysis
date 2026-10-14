package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.categorize.CategorizationService.RulesResult;
import com.finalysis.categorize.CategorizationService.RunResult;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stages 2–3 against the database (docs/plans/phase-4.md §1.2, §1.3): after an import, on demand and at
 * startup; idempotent; the user always wins; rules recomputed, AI kept; merchant memory. Imports commit for
 * real, as in production; every row and user rule is removed after each test, and seeds reactivated.
 */
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class CategorizationPipelineTest {

    private static final String CHECKING_APRIL = "ofx/northfield-checking_2026-04.ofx";
    private static final String ANSWER_KEY_APRIL = "statements/northfield-checking_2026-04.csv";

    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final ImportTestSupport support;
    private final CategorizeRows rows;
    private final RuleService ruleService;
    private final CategorizationListener listener;

    /** Calls the real service unless a test says otherwise; reset after each test. */
    @MockitoSpyBean
    private CategorizationService categorization;

    CategorizationPipelineTest(MockMvc mockMvc, JsonMapper jsonMapper, AccountRepository accounts, JdbcTemplate jdbc,
                               MerchantCleaner cleaner, RuleService ruleService, CategorizationListener listener) {
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.rows = new CategorizeRows(jdbc, cleaner);
        this.ruleService = ruleService;
        this.listener = listener;
    }

    @AfterEach
    void cleanUp() {
        reset(categorization);
        support.deleteCreatedAccounts();
        rows.deleteAccounts();
        jdbc.update("DELETE FROM categorization_rule WHERE source <> 'SEED'");
        jdbc.update("UPDATE categorization_rule SET active = true WHERE source = 'SEED' AND NOT active");
    }

    private record Categorized(long id, String description, String category, String source, Long ruleId,
                               Object categorizedAt) {}

    private List<Categorized> categorized(long accountId) {
        return jdbc.query("""
                SELECT t.id, t.raw_description, c.name, t.category_source, t.category_rule_id, t.categorized_at
                FROM txn t LEFT JOIN category c ON c.id = t.category_id
                WHERE t.account_id = ? ORDER BY t.id""",
                (rs, n) -> new Categorized(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, Long.class), rs.getObject(6)), accountId);
    }

    private long importCheckingApril() throws Exception {
        long account = support.account(AccountType.CHECKING, "1001");
        String body = support.upload(account, "april.ofx", ImportTestSupport.sample(CHECKING_APRIL))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(jsonMapper.readTree(body).get("inserted").asInt()).isPositive();
        return account;
    }

    // ------------------------------------------------------------------ after an import

    @Test
    void anImportIsCategorizedByTheSeedsAsTheAnswerKeySays() throws Exception {
        long account = importCheckingApril();
        Map<String, String> expected = new HashMap<>();
        for (String[] row : ImportTestSupport.answerKey(ANSWER_KEY_APRIL)) {
            expected.put(row[3], row[5]);
        }

        List<Categorized> txns = categorized(account);
        assertThat(txns).hasSize(ImportTestSupport.answerKey(ANSWER_KEY_APRIL).size());
        for (Categorized txn : txns) {
            assertThat(txn.category()).as(txn.description()).isEqualTo(expected.get(txn.description()));
            assertThat(txn.source()).isEqualTo("RULE");
            assertThat(txn.ruleId()).isNotNull();
            assertThat(txn.categorizedAt()).isNotNull();
        }
    }

    @Test
    void aSecondPassChangesNothing() throws Exception {
        long account = importCheckingApril();
        List<Categorized> before = categorized(account);

        RulesResult again = categorization.categorizeAll();
        RunResult run = categorization.run(false);

        assertThat(again.ruleCategorized()).isZero();
        assertThat(again.ruleCleared()).isZero();
        assertThat(again.memoryApplied()).isZero();
        assertThat(run).isEqualTo(new RunResult(0, 0, 0, 0));
        assertThat(categorized(account)).as("not even categorized_at moves").isEqualTo(before);
    }

    @Test
    void aRulesFailureLeavesTheImportAndLogsNoStatementData(CapturedOutput output) throws Exception {
        doThrow(new IllegalStateException("PARKSIDE PROPERTY MGMT -1850.00")).when(categorization)
                .categorizeStatement(anyLong());

        long account = importCheckingApril();

        assertThat(categorized(account)).isNotEmpty().allMatch(txn -> txn.category() == null);
        assertThat(support.count("SELECT count(*) FROM txn WHERE account_id = ? AND merchant IS NOT NULL", account))
                .as("cleaning ran").isPositive();
        assertThat(output.getOut()).contains("Rules after the import of statement")
                .contains("java.lang.IllegalStateException")
                .doesNotContain("PARKSIDE").doesNotContain("1850");
    }

    @Test
    void theStartupPassCategorizesRowsImportedBeforeTheRulesExisted() throws Exception {
        long account = importCheckingApril();
        List<Categorized> categorized = categorized(account);
        jdbc.update("""
                UPDATE txn SET category_id = NULL, category_source = NULL, category_rule_id = NULL, categorized_at = NULL
                WHERE account_id = ?""", account);

        listener.onReady();

        assertThat(categorized(account)).extracting(Categorized::category)
                .isEqualTo(categorized.stream().map(Categorized::category).toList());
    }

    // ------------------------------------------------------------------ recomputing

    @Test
    void deactivatingASeedClearsItsTxnsAndReactivatingRestoresThem() throws Exception {
        long account = importCheckingApril();
        long rent = rows.seed("\\bRENT\\b(?! A CAR)", "OUT");
        List<Long> rentTxns = categorized(account).stream().filter(txn -> Long.valueOf(rent).equals(txn.ruleId()))
                .map(Categorized::id).toList();
        assertThat(rentTxns).isNotEmpty();

        ruleService.update(rent, new UpdateRuleRequest(null, null, null, null, null, null, false));

        assertThat(rentTxns).allSatisfy(id -> {
            assertThat(rows.category(id)).isNull();
            assertThat(rows.source(id)).isNull();
            assertThat(rows.ruleId(id)).isNull();
        });

        ruleService.update(rent, new UpdateRuleRequest(null, null, null, null, null, null, true));

        assertThat(rentTxns).allSatisfy(id -> assertThat(rows.category(id)).isEqualTo("Housing"));
    }

    @Test
    void theUserAlwaysWins() {
        long txn = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        rows.categorize(txn, "Shopping", "USER");

        categorization.categorizeAll();
        categorization.run(true);

        assertThat(rows.category(txn)).isEqualTo("Shopping");
        assertThat(rows.source(txn)).isEqualTo("USER");
    }

    @Test
    void aRuleReplacesAnAiCategoryAndAnAiCategoryNoRuleMatchesStays() {
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        long insurer = rows.txn("ACH DEBIT ACME INS CO ID:9900112233 INDN:SAMPLE JANE", "-128.40");
        rows.categorize(cafe, "Shopping", "AI");
        rows.categorize(insurer, "Utilities", "AI");

        RulesResult result = categorization.categorizeAll();

        assertThat(rows.category(cafe)).isEqualTo("Dining");
        assertThat(rows.source(cafe)).isEqualTo("RULE");
        assertThat(rows.category(insurer)).as("re-asking the model costs money").isEqualTo("Utilities");
        assertThat(rows.source(insurer)).isEqualTo("AI");
        assertThat(result.ruleCategorized()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void deletingAUserRuleReEvaluatesItsTxns() {
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        long insurer = rows.txn("ACH DEBIT ACME INS CO ID:9900112233 INDN:SAMPLE JANE", "-128.40");
        RuleChangeResponse beansAreShopping = ruleService.create(new CreateRuleRequest(null, RuleMatchType.EXACT,
                "BLUE BEAN CAFE", null, null, rows.categoryId("Shopping")));
        RuleChangeResponse insurance = ruleService.create(new CreateRuleRequest(null, RuleMatchType.CONTAINS,
                "ACME INS", null, null, rows.categoryId("Utilities")));
        assertThat(rows.category(cafe)).as("a user rule outranks the CAFE seed").isEqualTo("Shopping");
        assertThat(rows.category(insurer)).isEqualTo("Utilities");

        ruleService.delete(beansAreShopping.rule().id());
        ruleService.delete(insurance.rule().id());

        assertThat(rows.category(cafe)).as("the seed takes it back").isEqualTo("Dining");
        assertThat(rows.ruleId(cafe)).isEqualTo(rows.seed("CAFE", "OUT"));
        assertThat(rows.category(insurer)).as("no rule left").isNull();
        assertThat(rows.source(insurer)).isNull();
    }

    // ------------------------------------------------------------------ merchant memory

    @Test
    void merchantMemoryCopiesAnAiCategoryToTheSameMerchantAndDirection() {
        long answered = rows.txn("ACH DEBIT ACME INS CO ID:9900112233 INDN:SAMPLE JANE", "-128.40");
        long sameMerchant = rows.txn("ACH DEBIT ACME INS CO ID:9900112233 INDN:SAMPLE JANE", "-130.10");
        long refund = rows.txn("ACH DEBIT ACME INS CO ID:9900112233 INDN:SAMPLE JANE", "12.00");
        long other = rows.txn("ACH DEBIT OTHER INS CO ID:9900112233 INDN:SAMPLE JANE", "-50.00");
        long linked = rows.txn("ACH DEBIT ACME INS CO ID:9900112233 INDN:SAMPLE JANE", "-99.00");
        jdbc.update("INSERT INTO transfer_link (out_txn_id, method, status, confirmed_at) VALUES (?, 'USER', 'UNMATCHED', now())",
                linked);
        rows.categorize(answered, "Utilities", "AI");

        RulesResult result = categorization.categorizeAll();

        assertThat(result.memoryApplied()).isEqualTo(1);
        assertThat(rows.category(sameMerchant)).isEqualTo("Utilities");
        assertThat(rows.source(sameMerchant)).isEqualTo("AI");
        assertThat(rows.category(refund)).as("other direction").isNull();
        assertThat(rows.category(other)).as("other merchant").isNull();
        assertThat(rows.category(linked)).as("linked txns skip the AI, and so its memory").isNull();
        assertThat(categorization.categorizeAll().memoryApplied()).isZero();
    }

    @Test
    void memoryNeverCopiesAUserOrRuleCategory() {
        long user = rows.txn("ACH DEBIT ACME INS CO ID:9900112233 INDN:SAMPLE JANE", "-128.40");
        long other = rows.txn("ACH DEBIT ACME INS CO ID:9900112233 INDN:SAMPLE JANE", "-130.10");
        rows.categorize(user, "Utilities", "USER");

        categorization.categorizeAll();

        assertThat(rows.category(other)).as("a user's decision spreads through a rule (step 3), not memory").isNull();
    }

    // ------------------------------------------------------------------ on demand

    @Test
    void anOnDemandRunCleansMissingMerchantsThenCategorizes() {
        long txn = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        jdbc.update("UPDATE txn SET merchant = NULL WHERE id = ?", txn);

        RunResult result = categorization.run(false);

        assertThat(result.merchantsCleaned()).isEqualTo(1);
        assertThat(result.ruleCategorized()).isEqualTo(1);
        assertThat(rows.category(txn)).isEqualTo("Dining");
    }
}
