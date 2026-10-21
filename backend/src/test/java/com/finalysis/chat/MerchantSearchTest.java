package com.finalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.analytics.DrillDownFilter;
import com.finalysis.analytics.DrillDownService;
import com.finalysis.analytics.MerchantTotalsResponse;
import com.finalysis.analytics.MerchantTotalsService;
import com.finalysis.analytics.TrendMeasure;
import com.finalysis.categorize.MerchantEmbeddingRepository;
import com.finalysis.categorize.MerchantIndexRefresh;
import com.finalysis.chat.outbound.OutboundTurn;
import com.finalysis.chat.outbound.OutboundTurnFactory;
import com.finalysis.chat.tool.FigureBasis;
import com.finalysis.chat.tool.RefRegistry;
import com.finalysis.support.ChatIntegrationTest;
import com.finalysis.support.FakeEmbeddingModel;
import com.finalysis.support.ImportTestSupport;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Meaning-based search and merchant totals (CHT-02; docs/plans/phase-6.md §4.3, §3.2) on the analytics fixture, through
 * {@link ToolExecutor}, with the deterministic {@link FakeEmbeddingModel}: chosen neighbours of the search text
 * ({@code RESTAURANT}: FIXTURE DINER at 0.82, FIXTURE GROCER at 0.55, FIXTURE BOOKSHOP at 0.30, below the 0.40 floor;
 * {@code DINER}: CLOUDBOX STORAGE at 0.70), every other merchant near-orthogonal. The index is filled by categorization's
 * refresh before each test. No model is called: the context has no Bedrock client and {@code NoCloudAiGuard} holds.
 */
@ChatIntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class MerchantSearchTest {

    static final String MODEL = "test-embedding-model";
    static final YearMonth FROM = YearMonth.of(2026, 1);
    static final YearMonth TO = YearMonth.of(2026, 4);
    static final String RANGE = "\"from\": \"2026-01\", \"to\": \"2026-04\"";

    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ToolExecutor executor;
    private final OutboundTurnFactory turns;
    private final FakeEmbeddingModel embeddingModel;
    private final MerchantIndexRefresh refresh;
    private final MerchantEmbeddingRepository embeddings;
    private final MerchantTotalsService merchantTotals;
    private final DrillDownService drillDown;

    MerchantSearchTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper json,
                       ToolExecutor executor, OutboundTurnFactory turns, FakeEmbeddingModel embeddingModel,
                       MerchantIndexRefresh refresh, MerchantEmbeddingRepository embeddings,
                       MerchantTotalsService merchantTotals, DrillDownService drillDown) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, json);
        this.jdbc = jdbc;
        this.json = json;
        this.executor = executor;
        this.turns = turns;
        this.embeddingModel = embeddingModel;
        this.refresh = refresh;
        this.embeddings = embeddings;
        this.merchantTotals = merchantTotals;
        this.drillDown = drillDown;
    }

    @BeforeAll
    void importFixture() throws Exception {
        fixture.load();
    }

    @AfterAll
    void deleteAccounts() {
        jdbc.update("DELETE FROM merchant_embedding");
        embeddingModel.reset();
        support.deleteCreatedAccounts();
    }

    @BeforeEach
    void fillTheIndex() {
        jdbc.update("DELETE FROM merchant_embedding");
        embeddingModel.reset()
                .near("FIXTURE DINER", "RESTAURANT", 0.82)
                .near("FIXTURE GROCER", "RESTAURANT", 0.55)
                .near("FIXTURE BOOKSHOP", "RESTAURANT", 0.30)
                .near("CLOUDBOX STORAGE", "DINER", 0.70);
        assertThat(refresh.refresh().embedded()).isPositive();
    }

    private ToolOutcome call(OutboundTurn turn, String tool, String arguments) {
        return executor.execute(new ToolCall(tool, arguments), turn);
    }

    private JsonNode result(OutboundTurn turn, String tool, String arguments) {
        ToolOutcome outcome = call(turn, tool, arguments);
        assertThat(outcome.status()).as(tool + " " + outcome.json()).isEqualTo(ToolStatus.OK);
        return json.readTree(outcome.json()).get("result");
    }

    private static List<String> merchants(JsonNode result) {
        return result.get("candidates").valueStream().map(c -> c.get("merchant").stringValue()).toList();
    }

    /** {@code get_top_merchants}' figures for a stored merchant over the fixture's four months. */
    private MerchantTotalsResponse.MerchantTotal top(String merchant, Optional<Long> account) {
        return merchantTotals.merchants(Optional.of(FROM), Optional.of(TO), account, Optional.empty(), 50).items()
                .stream().filter(m -> merchant.equals(m.merchant())).findFirst().orElseThrow();
    }

    /** A cell's figure as its drill-downs add it up: Σ sign × component of each drill-down's total. */
    private BigDecimal fromDrillDowns(OutboundTurn turn, String ref, String field) {
        RefRegistry.Cell cell = (RefRegistry.Cell) turn.refs().target(ref).orElseThrow();
        FigureBasis basis = cell.figures().get(field);
        assertThat(basis.relation()).isEqualTo(FigureBasis.Relation.SUM);
        BigDecimal sum = BigDecimal.ZERO;
        for (FigureBasis.Term term : basis.terms()) {
            Map<String, String> p = term.drillDown();
            var total = drillDown.transactions(new DrillDownFilter(Optional.empty(),
                    Optional.ofNullable(p.get("from")).map(YearMonth::parse),
                    Optional.ofNullable(p.get("to")).map(YearMonth::parse), Optional.empty(),
                    Optional.ofNullable(p.get("measure")).map(TrendMeasure::valueOf),
                    Optional.ofNullable(p.get("accountId")).map(Long::valueOf), Optional.empty(),
                    Optional.ofNullable(p.get("merchant")), false, 0, 1)).total();
            BigDecimal part = switch (basis.component()) {
                case NET -> total.net();
                case COUNT -> BigDecimal.valueOf(total.count());
                case MONEY_OUT -> total.moneyOut();
                case MONEY_IN -> total.moneyIn();
            };
            sum = sum.add(part.multiply(BigDecimal.valueOf(term.sign())));
        }
        return sum;
    }

    // ------------------------------------------------------------------ search_transactions

    @Test
    void meaningFindsTheChosenNeighboursNearestFirstAboveTheFloor() {
        OutboundTurn turn = turns.start();
        JsonNode result = result(turn, "search_transactions", "{\"query\": \"restaurant\", " + RANGE + ", \"limit\": 10}");

        assertThat(result.get("byMeaning").booleanValue()).isTrue();
        assertThat(merchants(result)).as("BOOKSHOP is below the floor").containsExactly("FIXTURE DINER",
                "FIXTURE GROCER");
        JsonNode diner = result.get("candidates").get(0);
        assertThat(diner.get("match").stringValue()).isEqualTo("MEANING");
        assertThat(diner.get("similarity").stringValue()).isEqualTo("0.82");
        assertThat(result.get("candidates").get(1).get("similarity").stringValue()).isEqualTo("0.55");
        assertThat(result.get("from").stringValue()).isEqualTo("2026-01");
        assertThat(result.get("to").stringValue()).isEqualTo("2026-04");

        // the same figures as get_top_merchants, and every one adds up from its drill-downs
        for (JsonNode candidate : result.get("candidates")) {
            MerchantTotalsResponse.MerchantTotal top = top(candidate.get("merchant").stringValue(), Optional.empty());
            assertThat(candidate.get("spent").stringValue()).isEqualTo(top.spent().toPlainString());
            assertThat(candidate.get("count").intValue()).isEqualTo(top.count());
            assertThat(candidate.get("category").stringValue()).isEqualTo(top.category().name());
            String ref = candidate.get("ref").stringValue();
            assertThat(fromDrillDowns(turn, ref, "spent")).isEqualByComparingTo(top.spent());
            assertThat(fromDrillDowns(turn, ref, "count").intValue()).isEqualTo(top.count());
        }
        assertThat(embeddingModel.inputs().getLast()).isEqualTo("RESTAURANT");
    }

    @Test
    void nameMatchesComeFirstWithoutASimilarityThenMeaning() {
        JsonNode result = result(turns.start(), "search_transactions", "{\"query\": \"diner\", \"limit\": 10}");

        assertThat(merchants(result)).containsExactly("FIXTURE DINER", "CLOUDBOX STORAGE");
        assertThat(result.get("candidates").get(0).get("match").stringValue()).isEqualTo("NAME");
        assertThat(result.get("candidates").get(0).get("similarity").isNull()).isTrue();
        assertThat(result.get("candidates").get(1).get("match").stringValue()).isEqualTo("MEANING");
        assertThat(result.get("candidates").get(1).get("similarity").stringValue()).isEqualTo("0.70");
    }

    @Test
    void theLimitCapsTheCandidates() {
        JsonNode result = result(turns.start(), "search_transactions", "{\"query\": \"restaurant\", \"limit\": 1}");

        assertThat(merchants(result)).containsExactly("FIXTURE DINER");
    }

    @Test
    void onlyVectorsOfTheSearchTextsModelAreCompared() {
        // STREAMCO stored with another model's vector equal to the search's own: similarity 1.00, but not comparable
        embeddings.upsert("STREAMCO", "other-embedding-model", "0".repeat(64), embeddingModel.vector("RESTAURANT"));

        JsonNode result = result(turns.start(), "search_transactions", "{\"query\": \"restaurant\", \"limit\": 10}");

        assertThat(merchants(result)).containsExactly("FIXTURE DINER", "FIXTURE GROCER");
    }

    @Test
    void coverageIsCountedInSql() {
        int merchants = jdbc.queryForObject("SELECT count(DISTINCT merchant) FROM txn WHERE merchant IS NOT NULL",
                Integer.class);
        int indexed = jdbc.queryForObject("SELECT count(*) FROM merchant_embedding e WHERE e.model = ? "
                + "AND EXISTS (SELECT 1 FROM txn t WHERE t.merchant = e.merchant)", Integer.class, MODEL);

        JsonNode result = result(turns.start(), "search_transactions", "{\"query\": \"restaurant\", \"limit\": 1}");

        assertThat(result.get("merchants").intValue()).isEqualTo(merchants);
        assertThat(result.get("indexedMerchants").intValue()).isEqualTo(indexed).isLessThanOrEqualTo(merchants)
                .isPositive();

        jdbc.update("DELETE FROM merchant_embedding WHERE merchant = 'FIXTURE DINER'");
        JsonNode after = result(turns.start(), "search_transactions", "{\"query\": \"restaurant\", \"limit\": 10}");
        assertThat(after.get("indexedMerchants").intValue()).isEqualTo(indexed - 1);
        assertThat(merchants(after)).as("an unindexed merchant isn't found by meaning").containsExactly(
                "FIXTURE GROCER");
    }

    /** §4.3 point 1: the search text is redacted like the question, then checked as a merchant would be. */
    @Test
    void theSearchTextLeavesRedactedOrNotAtAll() {
        result(turns.start(), "search_transactions", "{\"query\": \"Fixture Checking coffee\", \"limit\": 3}");
        String sent = embeddingModel.inputs().getLast();
        assertThat(sent).contains("CHECKING-1").contains("COFFEE").doesNotContain("FIXTURE");

        int before = embeddingModel.inputs().size();
        for (String refused : List.of("café ☕", "12345678", "ann@example.com")) {
            ToolOutcome outcome = call(turns.start(), "search_transactions",
                    json.createObjectNode().put("query", refused).put("limit", 3).toString());
            assertThat(outcome.status()).as(refused).isEqualTo(ToolStatus.INVALID_ARGUMENTS);
            JsonNode field = json.readTree(outcome.json()).get("fields").get(0);
            assertThat(field.get("name").stringValue()).isEqualTo("query");
            assertThat(field.get("message").stringValue()).startsWith("this search text can't be sent");
            assertThat(outcome.json()).doesNotContain(refused).doesNotContain("12345678").doesNotContain("ann@");
        }
        assertThat(embeddingModel.inputs()).as("nothing refused was embedded").hasSize(before);
    }

    @Test
    void aSearchIsEmbeddedOncePerTurnAndLogsItsTokens(CapturedOutput output) {
        OutboundTurn turn = turns.start();
        int before = embeddingModel.inputs().size();

        result(turn, "search_transactions", "{\"query\": \"restaurant\", \"limit\": 2}");
        result(turn, "search_transactions", "{\"query\": \"restaurant\", \"limit\": 5}");

        assertThat(embeddingModel.inputs()).hasSize(before + 1);
        assertThat(output).containsPattern("ai call role=embedding model=" + MODEL
                + " items=1 inputTokens=10 ms=\\d+ outcome=OK");
    }

    @Test
    void aFailedEmbeddingSearchesByNameOnly(CapturedOutput output) {
        embeddingModel.failingWith(new IllegalStateException("throttled RESTAURANT"));

        JsonNode result = result(turns.start(), "search_transactions", "{\"query\": \"diner\", \"limit\": 10}");

        assertThat(result.get("byMeaning").booleanValue()).isFalse();
        assertThat(merchants(result)).containsExactly("FIXTURE DINER");
        assertThat(output).containsPattern("ai call role=embedding model=" + MODEL
                + " items=1 inputTokens=n/a ms=\\d+ outcome=ERROR error=IllegalStateException");
        assertThat(output).contains("chat search embedding failed (IllegalStateException); searching by name only")
                .doesNotContain("throttled");
    }

    /**
     * Two stored merchants that go out as one text are one candidate, and every figure for the text covers both: the
     * search, the merchant totals and their drill-downs agree; the drill-down filter, which takes one merchant, says to
     * use the totals instead.
     */
    @Test
    void aTextStandingForTwoStoredMerchantsCoversBoth() {
        MerchantTotalsResponse.MerchantTotal whole = top("FIXTURE DINER", Optional.empty());
        int moved = jdbc.update("UPDATE txn SET merchant = 'FIXTURE DINER 8812' WHERE merchant = 'FIXTURE DINER' "
                + "AND txn_date < '2026-03-01'");
        try {
            assertThat(moved).isPositive();
            OutboundTurn turn = turns.start();

            JsonNode search = result(turn, "search_transactions", "{\"query\": \"diner\", " + RANGE + ", \"limit\": 10}");
            JsonNode candidate = search.get("candidates").get(0);
            assertThat(candidate.get("merchant").stringValue()).isEqualTo("FIXTURE DINER");
            assertThat(merchants(search)).doesNotHaveDuplicates();
            assertThat(candidate.get("spent").stringValue()).isEqualTo(whole.spent().toPlainString());
            assertThat(candidate.get("count").intValue()).isEqualTo(whole.count());
            RefRegistry.Cell cell = (RefRegistry.Cell) turn.refs().target(candidate.get("ref").stringValue())
                    .orElseThrow();
            assertThat(cell.drillDowns()).extracting(d -> d.get("merchant"))
                    .containsExactly("FIXTURE DINER", "FIXTURE DINER 8812");
            assertThat(fromDrillDowns(turn, candidate.get("ref").stringValue(), "spent"))
                    .isEqualByComparingTo(whole.spent());

            JsonNode totals = result(turn, "get_merchant_totals", "{\"merchants\": [\"FIXTURE DINER\"], " + RANGE + "}");
            assertThat(totals.get("merchants").get(0).get("spent").stringValue())
                    .isEqualTo(whole.spent().toPlainString());
            assertThat(totals.get("merchants").get(0).get("ref").stringValue())
                    .as("the same cell, the same ref").isEqualTo(candidate.get("ref").stringValue());

            ToolOutcome listed = call(turn, "list_transactions", "{\"merchant\": \"FIXTURE DINER\", \"limit\": 5}");
            assertThat(listed.status()).isEqualTo(ToolStatus.INVALID_ARGUMENTS);
            assertThat(listed.json()).contains("get_merchant_totals adds them up");
        } finally {
            jdbc.update("UPDATE txn SET merchant = 'FIXTURE DINER' WHERE merchant = 'FIXTURE DINER 8812'");
        }
    }

    // ------------------------------------------------------------------ get_merchant_totals

    @Test
    void merchantTotalsAreEachAndCombinedFromTheDrillDowns() {
        OutboundTurn turn = turns.start();
        JsonNode result = result(turn, "get_merchant_totals",
                "{\"merchants\": [\"FIXTURE DINER\", \"STREAMCO\", \"FIXTURE GROCER\"], " + RANGE + "}");

        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (JsonNode merchant : result.get("merchants")) {
            MerchantTotalsResponse.MerchantTotal top = top(merchant.get("merchant").stringValue(), Optional.empty());
            assertThat(merchant.get("spent").stringValue()).isEqualTo(top.spent().toPlainString());
            assertThat(merchant.get("count").intValue()).isEqualTo(top.count());
            sum = sum.add(top.spent());
            count += top.count();
        }
        JsonNode combined = result.get("combined");
        assertThat(new BigDecimal(combined.get("spent").stringValue())).isEqualByComparingTo(sum);
        assertThat(combined.get("count").intValue()).isEqualTo(count);
        String ref = combined.get("ref").stringValue();
        assertThat(fromDrillDowns(turn, ref, "spent")).isEqualByComparingTo(sum);
        assertThat(fromDrillDowns(turn, ref, "count").intValue()).isEqualTo(count);
    }

    @Test
    void merchantTotalsFollowTheAccountFilter() {
        long card = jdbc.queryForObject("SELECT id FROM account WHERE last_four = '5201'", Long.class);
        JsonNode result = result(turns.start(), "get_merchant_totals",
                "{\"merchants\": [\"STREAMCO\"], \"account\": \"CARD-1\", " + RANGE + "}");

        MerchantTotalsResponse.MerchantTotal top = top("STREAMCO", Optional.of(card));
        assertThat(result.get("account").stringValue()).isEqualTo("CARD-1");
        assertThat(result.get("merchants").get(0).get("spent").stringValue()).isEqualTo(top.spent().toPlainString());
    }

    @Test
    void aNameTheModelKnowsFromTheQuestionResolvesAndAnInventedOneDoesNot() {
        assertThat(result(turns.start(), "get_merchant_totals", "{\"merchants\": [\"STREAMCO\"]}").get("merchants"))
                .hasSize(1);
        ToolOutcome invented = call(turns.start(), "get_merchant_totals",
                "{\"merchants\": [\"STREAMCO\", \"NETFLIX 999.99\"]}");
        assertThat(invented.status()).isEqualTo(ToolStatus.INVALID_ARGUMENTS);
        assertThat(invented.json()).contains("find it with search_transactions").doesNotContain("NETFLIX")
                .doesNotContain("999.99");
    }
}
