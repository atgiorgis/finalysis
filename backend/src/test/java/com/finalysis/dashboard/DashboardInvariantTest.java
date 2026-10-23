package com.finalysis.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.SampleDataImport;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;
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
 * UI-04's invariant for the donut (docs/plans/phase-7.md §3.2): on the sample data with the answer key's categories,
 * for every month March to October 2026, each slice's amount equals the total of the drill-down behind it (Other's,
 * the sum of its members' drill-downs), and the slices add up to the month's spending. Step 3: each slice, each of
 * Other's members and the income line equal their details panel's total, which equals the total of the drill-down the
 * panel names, over every page.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardInvariantTest {

    private static final YearMonth FIRST = YearMonth.of(2026, 3);
    private static final YearMonth LAST = YearMonth.of(2026, 10);

    private final MockMvc mockMvc;
    private final ImportTestSupport support;
    private final SampleDataImport sample;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    DashboardInvariantTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.mockMvc = mockMvc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.sample = new SampleDataImport(support, jsonMapper);
        this.jdbc = jdbc;
        this.json = jsonMapper;
    }

    @BeforeAll
    void importSampleData() throws Exception {
        assertThat(support.count("SELECT count(*) FROM statement")).isZero();
        Map<String, Long> accounts = sample.load();
        sample.applyAnswerKey(jdbc, accounts);
    }

    @AfterAll
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    private JsonNode read(String path) throws Exception {
        return json.readTree(mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    /** The drill-down's total over every page: {@code size=1} still totals every matching txn. */
    private BigDecimal drillDown(String params) throws Exception {
        return new BigDecimal(read("/api/analytics/transactions?size=1&" + params).get("total").get("net").asString());
    }

    @Test
    void everySliceEqualsItsDrillDownEveryMonth() throws Exception {
        int otherSlices = 0;
        int checked = 0;
        for (YearMonth m = FIRST; !m.isAfter(LAST); m = m.plusMonths(1)) {
            JsonNode slices = read("/api/dashboard?month=" + m).get("spendingSlices");
            BigDecimal sum = BigDecimal.ZERO;
            for (JsonNode slice : slices.get("rows")) {
                BigDecimal amount = new BigDecimal(slice.get("amount").asString());
                sum = sum.add(amount);
                BigDecimal rows = switch (slice.get("kind").asString()) {
                    case "CATEGORY" -> drillDown("month=" + m + "&bucket=EXPENSE&categoryId="
                            + slice.get("category").get("id").asLong());
                    case "OTHER" -> {
                        otherSlices++;
                        BigDecimal members = BigDecimal.ZERO;
                        for (JsonNode member : slice.get("members")) {
                            String bucket = member.get("category").get("name").asString().equals("Refunds & Rebates")
                                    ? "REFUND" : "EXPENSE";
                            members = members.add(drillDown("month=" + m + "&bucket=" + bucket + "&categoryId="
                                    + member.get("category").get("id").asLong()));
                        }
                        yield members;
                    }
                    case "UNCATEGORIZED" -> drillDown("month=" + m + "&bucket=UNCATEGORIZED&measure=SPENT");
                    default -> throw new AssertionError(slice);
                };
                assertThat(rows).as(m + " " + slice.get("kind").asString() + " " + slice.get("category"))
                        .isEqualByComparingTo(amount);
                checked++;
            }
            assertThat(sum).as(m + ": the slices add up to spending")
                    .isEqualByComparingTo(new BigDecimal(slices.get("spending").asString()))
                    .isEqualByComparingTo(drillDown("month=" + m + "&measure=SPENT"));
        }
        assertThat(checked).as("slices checked").isGreaterThan(8 * 3);
        assertThat(otherSlices).as("the sample data has months with more than six categories").isPositive();
    }

    /** The details panel's total, checked against the drill-down it names, run as named. */
    private JsonNode details(String params, String figure) throws Exception {
        JsonNode details = read("/api/dashboard/details?" + params);
        assertThat(details.get("total").asString()).as(params).isEqualTo(figure);
        JsonNode drillDown = details.get("drillDown");
        assertThat(drillDown.get("path").asString()).isEqualTo("/api/analytics/transactions");
        StringBuilder query = new StringBuilder("size=200");
        drillDown.get("params").properties().forEach(e -> query.append('&').append(e.getKey()).append('=')
                .append(e.getValue().asString()));
        JsonNode rows = read("/api/analytics/transactions?" + query);
        assertThat(rows.get("total").get("net").asString()).as(params + ": the rows add up to the panel's total")
                .isEqualTo(figure);
        assertThat(drillDown.get("total")).as(params).isEqualTo(rows.get("total"));
        BigDecimal listed = BigDecimal.ZERO;
        for (JsonNode item : rows.get("items")) {
            listed = listed.add(new BigDecimal(item.get("amount").asString()));
        }
        assertThat(rows.get("totalPages").asInt()).as(params + ": one page holds them").isLessThanOrEqualTo(1);
        assertThat(listed).as(params + ": the listed rows add up").isEqualByComparingTo(new BigDecimal(figure));
        return details;
    }

    @Test
    void everyPanelEqualsItsSliceAndItsRowsEveryMonth() throws Exception {
        int panels = 0;
        for (YearMonth m = FIRST; !m.isAfter(LAST); m = m.plusMonths(1)) {
            JsonNode dashboard = read("/api/dashboard?month=" + m);
            for (JsonNode slice : dashboard.get("spendingSlices").get("rows")) {
                switch (slice.get("kind").asString()) {
                    case "CATEGORY" -> {
                        JsonNode d = details("month=" + m + "&categoryId=" + slice.get("category").get("id").asLong(),
                                slice.get("amount").asString());
                        assertThat(d.get("spendingSharePercent")).isEqualTo(slice.get("spendingSharePercent"));
                        panels++;
                    }
                    case "OTHER" -> {
                        for (JsonNode member : slice.get("members")) {
                            details("month=" + m + "&categoryId=" + member.get("category").get("id").asLong(),
                                    member.get("amount").asString());
                            panels++;
                        }
                    }
                    case "UNCATEGORIZED" -> {
                        details("month=" + m + "&uncategorized=true", slice.get("amount").asString());
                        panels++;
                    }
                    default -> throw new AssertionError(slice);
                }
            }
            details("month=" + m + "&line=INCOME", dashboard.get("totals").get("income").asString());
            panels++;
        }
        assertThat(panels).isGreaterThan(8 * 4);
    }
}
