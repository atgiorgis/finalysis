package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** No statements is not an error (docs/plans/phase-5.md §5.1): 200, nothing listed, no latest month. */
@IntegrationTest
@AutoConfigureMockMvc
class AnalyticsEmptyDatabaseTest {

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbc;

    AnalyticsEmptyDatabaseTest(MockMvc mockMvc, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.jdbc = jdbc;
    }

    @Test
    void everyEndpointAnswersWithNothing() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM statement", Integer.class)).isZero();

        mockMvc.perform(get("/api/analytics/months"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").isEmpty())
                .andExpect(jsonPath("$.latestMonth").isEmpty())
                .andExpect(jsonPath("$.latestCompleteMonth").isEmpty())
                .andExpect(jsonPath("$.months.length()").value(0));
        mockMvc.perform(get("/api/analytics/months").param("from", "2026-01").param("to", "2026-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range.from").value("2026-01"))
                .andExpect(jsonPath("$.months.length()").value(0));
        mockMvc.perform(get("/api/analytics/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").isEmpty())
                .andExpect(jsonPath("$.rows.length()").value(0));
        mockMvc.perform(get("/api/analytics/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").isEmpty())
                .andExpect(jsonPath("$.latestMonth").isEmpty());
        mockMvc.perform(get("/api/analytics/coverage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCoveredDay").isEmpty())
                .andExpect(jsonPath("$.latestCompleteMonth").isEmpty());
        mockMvc.perform(get("/api/analytics/months").param("from", "2026-03").param("to", "2026-01"))
                .andExpect(status().isBadRequest());
    }
}
