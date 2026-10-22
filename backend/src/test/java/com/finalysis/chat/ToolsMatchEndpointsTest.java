package com.finalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.chat.outbound.OutboundTurnFactory;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Each tool returns what the Phase 5 endpoint it wraps returns, on the analytics fixture (docs/plans/phase-6.md §3.1):
 * the same figures, the same bucket rule, the same defaults. The tool's arguments are the model's (aliases, category
 * names); the endpoint's are ids. {@code compare_periods} wraps a service with no endpoint, so it is held to the trends
 * endpoint's change from one month to the next, which §3.3 says it must equal.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolsMatchEndpointsTest {

    private final MockMvc mockMvc;
    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ToolExecutor executor;
    private final OutboundTurnFactory turns;
    private long checking;
    private long dining;

    ToolsMatchEndpointsTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper json,
                            ToolExecutor executor, OutboundTurnFactory turns) {
        this.mockMvc = mockMvc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, json);
        this.jdbc = jdbc;
        this.json = json;
        this.executor = executor;
        this.turns = turns;
    }

    @BeforeAll
    void importFixture() throws Exception {
        checking = fixture.load().accounts().get(AnalyticsFixture.CHECKING);
        dining = jdbc.queryForObject("SELECT id FROM category WHERE name = 'Dining'", Long.class);
    }

    @AfterAll
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @Test
    void getMonthTotalsIsTheMonthsEndpoint() throws Exception {
        JsonNode tool = result("get_month_totals", "{\"from\": \"2025-12\", \"to\": \"2026-04\"}");
        JsonNode api = api("/api/analytics/months?from=2025-12&to=2026-04");

        assertThat(fields(tool.get("months"), m -> List.of(m.get("month"), m.get("coverage"), m.get("income"),
                m.get("spending"), m.get("net"), m.get("savingsRatePercent"), m.get("refundsNet"),
                m.get("uncategorized").get("net"), m.get("unlinkedTransfersNet"))))
                .isEqualTo(fields(api.get("months"), m -> List.of(m.get("month"), m.get("coverage"), m.get("income"),
                        m.get("spending"), m.get("net"), m.get("savingsRatePercent"), m.get("refunds").get("net"),
                        m.get("uncategorized").get("net"), m.get("unlinkedTransfers").get("net"))));
        assertThat(tool.get("months").get(1).get("spending").stringValue()).as("expected-month-totals.csv")
                .isEqualTo("-1849.99");

        JsonNode scoped = result("get_month_totals", "{\"from\": \"2026-01\", \"to\": \"2026-04\", "
                + "\"account\": \"CHECKING-1\"}");
        JsonNode scopedApi = api("/api/analytics/months?from=2026-01&to=2026-04&accountId=" + checking);
        assertThat(fields(scoped.get("months"), m -> List.of(m.get("spending"), m.get("income"))))
                .isEqualTo(fields(scopedApi.get("months"), m -> List.of(m.get("spending"), m.get("income"))));
        assertThat(scoped.get("months").get(0).get("spending").stringValue()).isEqualTo("-1590.00");
    }

    @Test
    void getMonthTotalsDefaultsAsTheEndpoint() throws Exception {
        JsonNode tool = result("get_month_totals", "{}");
        JsonNode api = api("/api/analytics/months");

        assertThat(tool.get("from")).isEqualTo(api.get("range").get("from"));
        assertThat(tool.get("to")).isEqualTo(api.get("range").get("to"));
        assertThat(tool.get("months").size()).isEqualTo(api.get("months").size());
    }

    @Test
    void getCategoryTotalsIsTheCategoriesEndpoint() throws Exception {
        JsonNode tool = result("get_category_totals", "{\"from\": \"2026-01\", \"to\": \"2026-04\"}");
        JsonNode api = api("/api/analytics/categories?from=2026-01&to=2026-04");

        assertThat(fields(tool.get("rows"), r -> List.of(r.get("bucket"), r.get("category"), r.get("total"))))
                .isEqualTo(fields(api.get("rows"), r -> List.of(r.get("bucket"),
                        r.get("category").isNull() ? r.get("category") : r.get("category").get("name"),
                        r.get("total"))));
        assertThat(fields(tool.get("rows"), r -> fields(r.get("months"), c -> List.of(c.get("month"),
                c.get("moneyOut"), c.get("moneyIn"), c.get("net"), c.get("count")))))
                .isEqualTo(fields(api.get("rows"), r -> fields(r.get("months"), c -> List.of(c.get("month"),
                        c.get("flow").get("moneyOut"), c.get("flow").get("moneyIn"), c.get("flow").get("net"),
                        c.get("flow").get("count")))));

        JsonNode one = result("get_category_totals", "{\"from\": \"2026-01\", \"to\": \"2026-04\", "
                + "\"category\": \"Dining\", \"account\": \"CHECKING-1\"}");
        JsonNode oneApi = api("/api/analytics/categories?from=2026-01&to=2026-04&categoryId=" + dining
                + "&accountId=" + checking);
        assertThat(fields(one.get("rows"), r -> List.of(r.get("bucket"), r.get("total"))))
                .isEqualTo(fields(oneApi.get("rows"), r -> List.of(r.get("bucket"), r.get("total"))));
    }

    @Test
    void getAccountTotalsIsTheAccountsEndpoint() throws Exception {
        JsonNode tool = result("get_account_totals", "{\"from\": \"2026-01\", \"to\": \"2026-04\"}");
        JsonNode api = api("/api/analytics/accounts?from=2026-01&to=2026-04");

        assertThat(fields(tool.get("accounts"), a -> fields(a.get("months"), m -> List.of(m.get("month"),
                m.get("income"), m.get("spending"), m.get("net"), m.get("coverage")))))
                .isEqualTo(fields(api.get("accounts"), a -> fields(a.get("months"), m -> List.of(m.get("month"),
                        m.get("income"), m.get("spending"), m.get("net"), m.get("coverage")))));
        assertThat(fields(tool.get("accounts"), a -> List.of(a.get("account"))).toString())
                .contains("CHECKING-1").contains("CARD-1");
    }

    @Test
    void getTopMerchantsIsTheMerchantsEndpoint() throws Exception {
        JsonNode tool = result("get_top_merchants", "{\"from\": \"2026-01\", \"to\": \"2026-04\", \"limit\": 5}");
        JsonNode api = api("/api/analytics/merchants?from=2026-01&to=2026-04&limit=5");

        assertThat(fields(tool.get("items"), m -> List.of(m.get("spent"), m.get("count"),
                m.get("firstDate"), m.get("lastDate"))))
                .isEqualTo(fields(api.get("items"), m -> List.of(m.get("spent"), m.get("count"),
                        m.get("firstDate"), m.get("lastDate"))));
        // Merchants go out as their redacted text, so the rows are matched by their figures and order.
        for (String total : List.of("othersSpent", "othersCount", "totalSpent", "totalCount")) {
            assertThat(tool.get(total)).as(total).isEqualTo(api.get(total));
        }
    }

    @Test
    void getTrendsIsTheTrendsEndpoint() throws Exception {
        JsonNode tool = result("get_trends", "{\"month\": \"2026-03\"}");
        JsonNode api = api("/api/analytics/trends?month=2026-03");

        Function<JsonNode, List<JsonNode>> figures = r -> List.of(r.get("line"), r.get("measure"), r.get("amount"),
                r.get("previousMonth"), r.get("changeAmount"), r.get("changePercent"), r.get("trailing3").get("amount"),
                r.get("trailing6").get("amount"), r.get("sameMonthLastYear"));
        assertThat(fields(tool.get("rows"), figures)).isEqualTo(fields(api.get("rows"), figures));

        JsonNode dining = result("get_trends", "{\"month\": \"2026-03\", \"category\": \"Dining\"}");
        JsonNode diningApi = api("/api/analytics/trends?month=2026-03&categoryId=" + this.dining);
        assertThat(fields(dining.get("rows"), figures)).isEqualTo(fields(diningApi.get("rows"), figures));
    }

    @Test
    void comparePeriodsEqualsTheTrendsChange() throws Exception {
        JsonNode trends = api("/api/analytics/trends?month=2026-03");
        JsonNode spending = row(trends, "TOTAL_SPENDING");

        JsonNode tool = result("compare_periods", "{\"measure\": \"SPENDING\", \"a\": {\"month\": \"2026-03\"}, "
                + "\"b\": {\"month\": \"2026-02\"}}");

        assertThat(tool.get("a").get("value")).isEqualTo(spending.get("amount"));
        assertThat(tool.get("b").get("value")).isEqualTo(spending.get("previousMonth"));
        assertThat(tool.get("difference")).isEqualTo(spending.get("changeAmount"));
        assertThat(tool.get("changePercent")).isEqualTo(spending.get("changePercent"));

        JsonNode diningTrend = api("/api/analytics/trends?month=2026-03&categoryId=" + dining).get("rows").valueStream()
                .filter(r -> r.get("line").stringValue().equals("CATEGORY")
                        && r.get("measure").stringValue().equals("SPENT")).findFirst().orElseThrow();
        JsonNode diningTool = result("compare_periods", "{\"measure\": \"CATEGORY_SPENT\", \"category\": \"Dining\", "
                + "\"a\": {\"month\": \"2026-03\"}, \"b\": {\"from\": \"2026-02\", \"to\": \"2026-02\"}}");
        assertThat(diningTool.get("difference")).isEqualTo(diningTrend.get("changeAmount"));
    }

    @Test
    void listTransactionsIsTheDrillDownEndpoint() throws Exception {
        JsonNode tool = result("list_transactions", "{\"month\": \"2026-03\", \"measure\": \"SPENT\", \"limit\": 5}");
        JsonNode api = api("/api/analytics/transactions?month=2026-03&measure=SPENT&size=5");

        assertThat(tool.get("total")).isEqualTo(api.get("total"));
        assertThat(fields(tool.get("items"), t -> List.of(t.get("date"), t.get("amount"), t.get("bucket"))))
                .isEqualTo(fields(api.get("items"), t -> List.of(t.get("txnDate"), t.get("amount"), t.get("bucket"))));
        assertThat(tool.get("truncated").booleanValue())
                .isEqualTo(api.get("total").get("count").intValue() > api.get("items").size());

        JsonNode dining = result("list_transactions", "{\"from\": \"2026-01\", \"to\": \"2026-04\", "
                + "\"category\": \"Dining\", \"account\": \"CHECKING-1\", \"limit\": 25}");
        JsonNode diningApi = api("/api/analytics/transactions?from=2026-01&to=2026-04&categoryId=" + this.dining
                + "&accountId=" + checking + "&size=25");
        assertThat(dining.get("total")).isEqualTo(diningApi.get("total"));
    }

    @Test
    void getRecurringChargesIsTheRecurringEndpoint() throws Exception {
        JsonNode tool = result("get_recurring_charges", "{\"status\": \"ALL\", \"limit\": 25}");
        JsonNode api = api("/api/analytics/recurring?status=ALL&limit=25");

        Function<JsonNode, List<JsonNode>> figures = s -> List.of(s.get("typicalAmount"), s.get("lastAmount"),
                s.get("fixedPrice"), s.get("occurrences"), s.get("firstDate"), s.get("lastDate"),
                s.get("nextExpectedDate"), s.get("status"));
        assertThat(fields(tool.get("items"), figures)).isNotEmpty().isEqualTo(fields(api.get("items"), figures));
        assertThat(tool.get("truncated")).isEqualTo(api.get("truncated"));
    }

    @Test
    void getAlertsIsTheAlertsEndpoint() throws Exception {
        JsonNode tool = result("get_alerts", "{\"from\": \"2026-01\", \"to\": \"2026-04\", \"limit\": 20}");
        JsonNode api = api("/api/alerts?from=2026-01&to=2026-04&limit=20");

        Function<JsonNode, List<JsonNode>> figures = a -> List.of(a.get("type"), a.get("month"),
                a.get("basis").get("observed"), a.get("basis").get("threshold"), a.get("dismissed"));
        assertThat(fields(tool.get("items"), figures)).isNotEmpty().isEqualTo(fields(api.get("items"), figures));
        api.get("counts").properties().forEach(e -> assertThat(tool.get("counts").valueStream()
                .filter(c -> c.get("type").stringValue().equals(e.getKey())).findFirst().orElseThrow().get("count"))
                .as(e.getKey()).isEqualTo(e.getValue()));
    }

    /**
     * Step 8b: without a period, {@code get_alerts} covers every month with data (the API's own default is 3 months),
     * from the month of the first covered day to the latest month, and says so in its {@code from} and {@code to}; it
     * lists exactly the alerts the endpoint lists over that range. {@code month} is one month.
     */
    @Test
    void getAlertsWithoutAPeriodCoversEveryMonthWithData() throws Exception {
        JsonNode coverage = api("/api/analytics/coverage");
        String earliest = coverage.get("accounts").valueStream().map(a -> a.get("firstDay").stringValue())
                .min(String::compareTo).orElseThrow().substring(0, 7);
        String latest = coverage.get("latestMonth").stringValue();

        JsonNode tool = result("get_alerts", "{\"limit\": 20}");
        assertThat(tool.get("from").stringValue()).isEqualTo(earliest);
        assertThat(tool.get("to").stringValue()).isEqualTo(latest);
        JsonNode api = api("/api/alerts?from=" + earliest + "&to=" + latest + "&limit=20");
        Function<JsonNode, List<JsonNode>> figures = a -> List.of(a.get("type"), a.get("month"),
                a.get("basis").get("observed"));
        assertThat(fields(tool.get("items"), figures)).isNotEmpty().isEqualTo(fields(api.get("items"), figures));
        assertThat(java.time.YearMonth.parse(earliest)).as("wider than the API's 3-month default")
                .isBefore(java.time.YearMonth.parse(latest).minusMonths(2));

        JsonNode march = result("get_alerts", "{\"month\": \"2026-03\", \"limit\": 20}");
        assertThat(march.get("from").stringValue()).isEqualTo("2026-03");
        assertThat(march.get("to").stringValue()).isEqualTo("2026-03");
        assertThat(fields(march.get("items"), figures))
                .isEqualTo(fields(api("/api/alerts?from=2026-03&to=2026-03&limit=20").get("items"), figures));
    }

    @Test
    void getDataCoverageIsTheCoverageEndpoint() throws Exception {
        JsonNode tool = result("get_data_coverage", "{}");
        JsonNode api = api("/api/analytics/coverage");

        for (String field : List.of("lastCoveredDay", "latestMonth", "latestCompleteMonth")) {
            assertThat(tool.get(field)).as(field).isEqualTo(api.get(field));
        }
        assertThat(fields(tool.get("accounts"), a -> List.of(a.get("firstDay"), a.get("covered"), a.get("gaps"))))
                .isEqualTo(fields(api.get("accounts"), a -> List.of(a.get("firstDay"), a.get("covered"),
                        a.get("gaps"))));
    }

    private JsonNode result(String tool, String arguments) {
        ToolOutcome outcome = executor.execute(new ToolCall(tool, arguments), turns.start());
        assertThat(outcome.status()).as(tool + " " + outcome.json()).isEqualTo(ToolStatus.OK);
        return json.readTree(outcome.json()).get("result");
    }

    private JsonNode api(String path) throws Exception {
        return json.readTree(mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private static JsonNode row(JsonNode trends, String line) {
        return trends.get("rows").valueStream().filter(r -> r.get("line").stringValue().equals(line)).findFirst()
                .orElseThrow();
    }

    private static <T> List<T> fields(JsonNode array, Function<JsonNode, T> pick) {
        List<T> values = new ArrayList<>();
        array.forEach(node -> values.add(pick.apply(node)));
        return values;
    }
}
