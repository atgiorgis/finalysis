package com.finalysis.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Without statements the dashboard answers 200 with nothing, for the default and for any month. */
@IntegrationTest
@AutoConfigureMockMvc
class DashboardEmptyDatabaseTest {

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbc;

    DashboardEmptyDatabaseTest(MockMvc mockMvc, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.jdbc = jdbc;
    }

    @Test
    void nothingToShow() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM statement", Integer.class)).isZero();

        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").isEmpty())
                .andExpect(jsonPath("$.totals").isEmpty())
                .andExpect(jsonPath("$.cashFlow.length()").value(0))
                .andExpect(jsonPath("$.spendingSlices.spending").value("0.00"))
                .andExpect(jsonPath("$.spendingSlices.rows.length()").value(0))
                .andExpect(jsonPath("$.recurring.active").value(0));
        mockMvc.perform(get("/api/dashboard").param("month", "2026-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-03"))
                .andExpect(jsonPath("$.totals").isEmpty());
    }
}
