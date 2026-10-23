package com.finalysis.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/dashboard} (docs/plans/phase-7.md §3.1) on the analytics fixture, January to April 2026: every
 * figure equals what its own endpoint returns for the same month, compared as the JSON strings both send.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardMatchesEndpointsTest {

    private final MockMvc mockMvc;
    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final JsonMapper json;

    DashboardMatchesEndpointsTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc,
                                  JsonMapper jsonMapper) {
        this.mockMvc = mockMvc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, jsonMapper);
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

    private static BigDecimal money(JsonNode node) {
        return new BigDecimal(node.asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-01", "2026-02", "2026-03", "2026-04"})
    void everyFigureIsItsEndpoints(String m) throws Exception {
        YearMonth month = YearMonth.parse(m);
        JsonNode dashboard = read("/api/dashboard?month=" + m);
        assertThat(dashboard.get("month").asString()).isEqualTo(m);

        // The month's totals and the cash-flow months, as /months returns them.
        JsonNode window = read("/api/analytics/months?from=" + month.minusMonths(5) + "&to=" + m).get("months");
        JsonNode totals = window.get(5);
        assertThat(dashboard.get("totals")).isEqualTo(totals);
        assertThat(dashboard.get("cashFlow").size()).isEqualTo(6);
        for (int i = 0; i < 6; i++) {
            JsonNode cell = dashboard.get("cashFlow").get(i);
            for (String field : List.of("month", "coverage", "income", "spending", "net")) {
                assertThat(cell.get(field)).as(field + " " + i).isEqualTo(window.get(i).get(field));
            }
        }

        // Categorized share: 100 − the uncategorized share by count.
        JsonNode uncategorized = totals.get("dataQuality").get("uncategorizedPercentOfCount");
        if (uncategorized.isNull()) {
            assertThat(dashboard.get("categorizedPercent").isNull()).isTrue();
        } else {
            assertThat(new BigDecimal(dashboard.get("categorizedPercent").asString()))
                    .isEqualTo(new BigDecimal("100.0").subtract(new BigDecimal(uncategorized.asString())));
        }

        // Slices: each category slice is its row; Other its members' rows; all of them add up to spending.
        JsonNode categories = read("/api/analytics/categories?from=" + m + "&to=" + m);
        JsonNode slices = dashboard.get("spendingSlices");
        assertThat(slices.get("spending")).isEqualTo(categories.get("spending")).isEqualTo(totals.get("spending"));
        BigDecimal sum = BigDecimal.ZERO;
        List<String> sliced = new ArrayList<>();
        for (JsonNode slice : slices.get("rows")) {
            sum = sum.add(money(slice.get("amount")));
            switch (slice.get("kind").asString()) {
                case "CATEGORY" -> {
                    JsonNode row = row(categories, "EXPENSE", slice.get("category").get("id").asLong());
                    assertThat(slice.get("amount")).isEqualTo(row.get("total").get("net"));
                    assertThat(slice.get("spendingSharePercent")).isEqualTo(row.get("spendingSharePercent"));
                    sliced.add(slice.get("category").get("name").asString());
                }
                case "OTHER" -> {
                    BigDecimal members = BigDecimal.ZERO;
                    for (JsonNode member : slice.get("members")) {
                        members = members.add(money(member.get("amount")));
                        sliced.add(member.get("category").get("name").asString());
                    }
                    assertThat(money(slice.get("amount"))).isEqualByComparingTo(members);
                }
                case "UNCATEGORIZED" -> assertThat(slice.get("amount"))
                        .isEqualTo(row(categories, "UNCATEGORIZED", null).get("total").get("moneyOut"));
                default -> throw new AssertionError(slice);
            }
        }
        assertThat(sum).as("the slices add up to spending").isEqualByComparingTo(money(slices.get("spending")));
        List<String> spendingRows = new ArrayList<>();
        for (JsonNode row : categories.get("rows")) {
            String bucket = row.get("bucket").asString();
            if (bucket.equals("EXPENSE") || bucket.equals("REFUND")) {
                spendingRows.add(row.get("category").get("name").asString());
            }
        }
        assertThat(sliced).as("every spending row in exactly one slice").containsExactlyInAnyOrderElementsOf(spendingRows);

        // Changes from last month, against the trends' total lines (spending and income) and the months (net, rate).
        JsonNode vs = dashboard.get("vsLastMonth");
        JsonNode previous = window.get(4);
        if (previous.get("coverage").asString().equals("NONE")) {
            assertThat(vs.isNull()).as("no comparison with a month without data").isTrue();
        } else {
            JsonNode trends = read("/api/analytics/trends?month=" + m).get("rows");
            assertThat(vs.get("previousMonth").asString()).isEqualTo(month.minusMonths(1).toString());
            assertChange(vs.get("spending"), line(trends, "TOTAL_SPENDING"));
            assertChange(vs.get("income"), line(trends, "TOTAL_INCOME"));
            assertThat(money(vs.get("net").get("difference")))
                    .isEqualByComparingTo(money(totals.get("net")).subtract(money(previous.get("net"))));
            assertThat(vs.get("net").get("previous")).isEqualTo(previous.get("net"));
            JsonNode rate = totals.get("savingsRatePercent");
            JsonNode previousRate = previous.get("savingsRatePercent");
            if (rate.isNull() || previousRate.isNull()) {
                assertThat(vs.get("savingsRatePoints").isNull()).isTrue();
            } else {
                assertThat(new BigDecimal(vs.get("savingsRatePoints").asString()))
                        .isEqualTo(new BigDecimal(rate.asString()).subtract(new BigDecimal(previousRate.asString())));
            }
        }

        // Alerts of the 3 months to the month, not dismissed; the newest 3.
        JsonNode alerts = read("/api/alerts?from=" + month.minusMonths(2) + "&to=" + m + "&limit=3");
        assertThat(dashboard.get("alerts").get("range")).isEqualTo(alerts.get("range"));
        assertThat(dashboard.get("alerts").get("counts")).isEqualTo(alerts.get("counts"));
        assertThat(dashboard.get("alerts").get("newest")).isEqualTo(alerts.get("items"));

        // The active recurring series: the first 6, and how many there are.
        JsonNode recurring = read("/api/analytics/recurring?status=ACTIVE&limit=100");
        JsonNode summary = dashboard.get("recurring");
        assertThat(summary.get("active").asInt()).isEqualTo(recurring.get("items").size());
        for (int i = 0; i < summary.get("items").size(); i++) {
            assertThat(summary.get("items").get(i)).isEqualTo(recurring.get("items").get(i));
        }
        assertThat(summary.get("items").size()).isEqualTo(Math.min(6, recurring.get("items").size()));
    }

    /** A trends line's change, as the dashboard's change: the same magnitudes, difference and percent. */
    private static void assertChange(JsonNode change, JsonNode line) {
        assertThat(change.get("previous")).isEqualTo(line.get("previousMonth"));
        assertThat(change.get("difference")).isEqualTo(line.get("changeAmount"));
        assertThat(change.get("changePercent")).isEqualTo(line.get("changePercent"));
    }

    private static JsonNode line(JsonNode trendRows, String line) {
        for (JsonNode row : trendRows) {
            if (row.get("line").asString().equals(line)) {
                return row;
            }
        }
        throw new AssertionError("no " + line + " line");
    }

    private static JsonNode row(JsonNode categories, String bucket, Long categoryId) {
        for (JsonNode row : categories.get("rows")) {
            boolean sameCategory = categoryId == null ? row.get("category").isNull()
                    : !row.get("category").isNull() && row.get("category").get("id").asLong() == categoryId;
            if (row.get("bucket").asString().equals(bucket) && sameCategory) {
                return row;
            }
        }
        throw new AssertionError("no " + bucket + " row for " + categoryId);
    }
}
