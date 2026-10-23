package com.finalysis.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/dashboard/details} (docs/plans/phase-7.md §3.2) on the analytics fixture: each part of the panel is
 * its own endpoint's figure (the category row, the trends' trailing average, the merchants, the six months), the
 * Refunds &amp; Rebates line in its REFUND bucket, and the requests it refuses.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardDetailsTest {

    private static final String DETAILS = "/api/dashboard/details";

    private final MockMvc mockMvc;
    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    DashboardDetailsTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.mockMvc = mockMvc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, jsonMapper);
        this.jdbc = jdbc;
        this.json = jsonMapper;
    }

    @BeforeAll
    void importFixture() throws Exception {
        assertThat(support.count("SELECT count(*) FROM statement")).isZero();
        fixture.load();
    }

    @AfterAll
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    private JsonNode read(String path) throws Exception {
        return json.readTree(mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private long category(String name) {
        return jdbc.queryForObject("SELECT id FROM category WHERE name = ?", Long.class, name);
    }

    private static BigDecimal money(JsonNode node) {
        return new BigDecimal(node.asString());
    }

    private static JsonNode find(JsonNode array, java.util.function.Predicate<JsonNode> test) {
        for (JsonNode node : array) {
            if (test.test(node)) {
                return node;
            }
        }
        return null;
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-02", "2026-03", "2026-04"})
    void aCategoryPanelIsItsEndpointsFigures(String m) throws Exception {
        YearMonth month = YearMonth.parse(m);
        long shopping = category("Shopping");
        JsonNode d = read(DETAILS + "?month=" + m + "&categoryId=" + shopping);

        assertThat(d.get("kind").asString()).isEqualTo("CATEGORY");
        assertThat(d.get("bucket").asString()).isEqualTo("EXPENSE");
        assertThat(d.get("category").get("name").asString()).isEqualTo("Shopping");

        // The month's row of the category endpoint: total and share.
        JsonNode monthRows = read("/api/analytics/categories?from=" + m + "&to=" + m + "&categoryId=" + shopping);
        JsonNode row = find(monthRows.get("rows"), r -> r.get("bucket").asString().equals("EXPENSE"));
        assertThat(d.get("total")).isEqualTo(row.get("total").get("net"));
        assertThat(d.get("spendingSharePercent")).isEqualTo(row.get("spendingSharePercent"));
        assertThat(money(d.get("amount"))).isEqualByComparingTo(money(d.get("total")).negate());

        // The average: the trends' trailing 3 months, the month's spent equal to the trends' amount.
        JsonNode trend = find(read("/api/analytics/trends?month=" + m + "&categoryId=" + shopping).get("rows"),
                r -> r.get("line").asString().equals("CATEGORY") && r.get("bucket").asString().equals("EXPENSE"));
        assertThat(d.get("amount")).isEqualTo(trend.get("amount"));
        JsonNode average = d.get("average");
        assertThat(average.get("amount")).isEqualTo(trend.get("trailing3").get("amount"));
        assertThat(average.get("monthsUsed")).isEqualTo(trend.get("trailing3").get("monthsUsed"));
        if (!average.get("amount").isNull()) {
            BigDecimal difference = money(d.get("amount")).subtract(money(average.get("amount")));
            assertThat(money(average.get("differenceFromAverage"))).isEqualByComparingTo(difference);
            if (money(average.get("amount")).signum() != 0) {
                assertThat(money(average.get("differencePercent"))).isEqualByComparingTo(difference
                        .multiply(BigDecimal.valueOf(100)).divide(money(average.get("amount")).abs(), 1,
                                RoundingMode.HALF_EVEN));
            }
        }

        // The six months: the category's cells over the range, as spent, with each month's coverage.
        JsonNode range = read("/api/analytics/categories?from=" + month.minusMonths(5) + "&to=" + m + "&categoryId="
                + shopping);
        JsonNode rangeRow = find(range.get("rows"), r -> r.get("bucket").asString().equals("EXPENSE"));
        JsonNode months = read("/api/analytics/months?from=" + month.minusMonths(5) + "&to=" + m).get("months");
        assertThat(d.get("trend").size()).isEqualTo(6);
        for (int i = 0; i < 6; i++) {
            JsonNode cell = d.get("trend").get(i);
            assertThat(cell.get("month").asString()).isEqualTo(month.minusMonths(5 - i).toString());
            assertThat(cell.get("coverage")).isEqualTo(months.get(i).get("coverage"));
            JsonNode source = find(rangeRow.get("months"), c -> c.get("month").equals(cell.get("month")));
            assertThat(money(cell.get("amount"))).isEqualByComparingTo(money(source.get("flow").get("net")).negate());
        }
        assertThat(d.get("trend").get(5).get("amount")).isEqualTo(d.get("amount"));

        // Top merchants: the merchants endpoint for the month and category.
        assertThat(d.get("topMerchants")).isEqualTo(read("/api/analytics/merchants?from=" + m + "&to=" + m
                + "&categoryId=" + shopping + "&limit=5"));

        // The drill-down it names lists exactly the rows behind the total.
        assertThat(d.get("drillDown").get("params").properties().stream().map(e -> e.getKey()).toList())
                .containsExactly("month", "bucket", "categoryId");
        assertThat(d.get("drillDown").get("total").get("net")).isEqualTo(d.get("total"));
    }

    /** March has a refund in Refunds & Rebates: its panel is the REFUND bucket's, money in, a negative share. */
    @Test
    void theRefundsLineIsItsRefundBucket() throws Exception {
        long refunds = category("Refunds & Rebates");
        mockMvc.perform(get(DETAILS).param("month", "2026-03").param("categoryId", "" + refunds))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucket").value("REFUND"))
                .andExpect(jsonPath("$.total").value("15.00"))
                .andExpect(jsonPath("$.amount").value("-15.00"))
                .andExpect(jsonPath("$.drillDown.params.bucket").value("REFUND"))
                .andExpect(jsonPath("$.drillDown.total.net").value("15.00"));
    }

    @Test
    void theIncomeAndUncategorizedPanels() throws Exception {
        JsonNode months = read("/api/analytics/months?from=2026-02&to=2026-02").get("months");
        mockMvc.perform(get(DETAILS).param("month", "2026-02").param("line", "INCOME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("INCOME"))
                .andExpect(jsonPath("$.total").value(months.get(0).get("income").asString()))
                .andExpect(jsonPath("$.spendingSharePercent").isEmpty())
                .andExpect(jsonPath("$.topMerchants").isEmpty())
                .andExpect(jsonPath("$.drillDown.params.measure").value("RECEIVED"))
                .andExpect(jsonPath("$.drillDown.total.net").value(months.get(0).get("income").asString()));
        // February's one uncategorized charge (AnalyticsApiTest: money out 68.00, review pending).
        mockMvc.perform(get(DETAILS).param("month", "2026-02").param("uncategorized", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("UNCATEGORIZED"))
                .andExpect(jsonPath("$.total").value("-68.00"))
                .andExpect(jsonPath("$.amount").value("68.00"))
                .andExpect(jsonPath("$.drillDown.params.bucket").value("UNCATEGORIZED"))
                .andExpect(jsonPath("$.drillDown.params.measure").value("SPENT"))
                .andExpect(jsonPath("$.drillDown.total.net").value("-68.00"));
    }

    @Test
    void aCategoryWithoutRowsThatMonthIsZero() throws Exception {
        JsonNode d = read(DETAILS + "?month=2025-12&categoryId=" + category("Housing"));
        assertThat(d.get("total").asString()).isEqualTo("0.00");
        assertThat(d.get("drillDown").get("total").get("count").asInt()).isZero();
    }

    @Test
    void theRequestsItRefuses() throws Exception {
        long shopping = category("Shopping");
        mockMvc.perform(get(DETAILS).param("month", "2026-03")).andExpect(status().isBadRequest());
        mockMvc.perform(get(DETAILS).param("month", "2026-03").param("categoryId", "" + shopping)
                .param("uncategorized", "true")).andExpect(status().isBadRequest());
        mockMvc.perform(get(DETAILS).param("categoryId", "" + shopping)).andExpect(status().isBadRequest());
        mockMvc.perform(get(DETAILS).param("month", "2026-05").param("categoryId", "" + shopping))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("month"));
        mockMvc.perform(get(DETAILS).param("month", "2026-03").param("line", "SPENDING"))
                .andExpect(status().isBadRequest());
        long unknown = jdbc.queryForObject("SELECT max(id) + 1000 FROM category", Long.class);
        mockMvc.perform(get(DETAILS).param("month", "2026-03").param("categoryId", "" + unknown))
                .andExpect(status().isNotFound());
    }
}
