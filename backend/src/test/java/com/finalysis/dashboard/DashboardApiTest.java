package com.finalysis.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/dashboard} on the analytics fixture (January to April 2026): the default month, the money as strings,
 * March's figures by hand, and the requests it refuses.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardApiTest {

    private static final String DASHBOARD = "/api/dashboard";

    private final MockMvc mockMvc;
    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;

    DashboardApiTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.mockMvc = mockMvc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, jsonMapper);
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

    @Test
    void theDefaultIsTheLatestMonthWithData() throws Exception {
        mockMvc.perform(get(DASHBOARD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-04"))
                .andExpect(jsonPath("$.totals.month").value("2026-04"))
                .andExpect(jsonPath("$.cashFlow.length()").value(6))
                .andExpect(jsonPath("$.cashFlow[0].month").value("2025-11"))
                .andExpect(jsonPath("$.cashFlow[0].coverage").value("NONE"))
                .andExpect(jsonPath("$.cashFlow[5].month").value("2026-04"));
    }

    /** March by hand (as in {@code MonthlyTotalsTest}): spending 1,809.48, Shopping 3.5% of it; money as strings. */
    @Test
    void marchByHand() throws Exception {
        mockMvc.perform(get(DASHBOARD).param("month", "2026-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.spending").value("-1809.48"))
                .andExpect(jsonPath("$.spendingSlices.spending").isString())
                .andExpect(jsonPath("$.spendingSlices.spending").value("-1809.48"))
                .andExpect(jsonPath("$.spendingSlices.rows[?(@.category.name == 'Shopping')].spendingSharePercent")
                        .value("3.5"))
                .andExpect(jsonPath("$.vsLastMonth.previousMonth").value("2026-02"))
                .andExpect(jsonPath("$.vsLastMonth.spending.difference").isString())
                .andExpect(jsonPath("$.vsLastMonth.spending.previous").value("1687.98"))
                .andExpect(jsonPath("$.vsLastMonth.spending.difference").value("121.50"));
    }

    /**
     * December 2025, the fixture's first month (a partial card statement): November has no data, so there is nothing
     * to compare with. January is compared with partial December, as the trends do (null only for a month without
     * data).
     */
    @Test
    void theFirstMonthHasNothingToCompareWith() throws Exception {
        mockMvc.perform(get(DASHBOARD).param("month", "2025-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.coverage").value("PARTIAL"))
                .andExpect(jsonPath("$.vsLastMonth").isEmpty());
        mockMvc.perform(get(DASHBOARD).param("month", "2026-01"))
                .andExpect(jsonPath("$.vsLastMonth.previousMonth").value("2025-12"))
                .andExpect(jsonPath("$.vsLastMonth.savingsRatePoints").isEmpty());
    }

    @Test
    void aMonthBeforeTheDataIsEmptyNotAnError() throws Exception {
        mockMvc.perform(get(DASHBOARD).param("month", "2025-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.coverage").value("NONE"))
                .andExpect(jsonPath("$.totals.spending").value("0.00"))
                .andExpect(jsonPath("$.spendingSlices.rows").isEmpty())
                .andExpect(jsonPath("$.vsLastMonth").isEmpty());
    }

    @Test
    void aMonthAfterTheDataIs400() throws Exception {
        mockMvc.perform(get(DASHBOARD).param("month", "2026-05"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("month"));
    }

    @Test
    void aMalformedMonthIs400() throws Exception {
        mockMvc.perform(get(DASHBOARD).param("month", "March"))
                .andExpect(status().isBadRequest());
    }
}
