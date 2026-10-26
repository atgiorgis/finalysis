package com.finalysis.chat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.support.ChatIntegrationTest;
import com.finalysis.support.MutableClock;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/chat/usage} (docs/plans/phase-7.md §3.4): today's spend is the ledger since local midnight, the same
 * sum the daily cap checks, with the configured caps. The clock is the sample data's "today", 2026-10-15 at noon.
 */
@ChatIntegrationTest
@AutoConfigureMockMvc
class ChatUsageApiTest {

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final MutableClock clock;

    ChatUsageApiTest(MockMvc mvc, JdbcTemplate jdbc, MutableClock clock) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @BeforeEach
    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE FROM chat_usage");
    }

    private void ledgerRow(String kind, String cost, LocalDateTime at) {
        jdbc.update("INSERT INTO chat_usage (kind, model, calls, estimated_cost, created_at) VALUES (?, 'm', 1, "
                + "CAST(? AS numeric), ?)", kind, cost, Timestamp.from(at.atZone(ZoneId.systemDefault()).toInstant()));
    }

    @Test
    void anEmptyLedgerIsZeroWithTheDefaultCaps() throws Exception {
        mvc.perform(get("/api/chat/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day").value("2026-10-15"))
                .andExpect(jsonPath("$.spentToday").value("0.000000"))
                .andExpect(jsonPath("$.maxTurnCost").value("0.15"))
                .andExpect(jsonPath("$.maxDailyCost").value("1.00"))
                .andExpect(jsonPath("$.resetsAt").exists())
                .andExpect(jsonPath("$.month").value("2026-10"))
                .andExpect(jsonPath("$.spentThisMonth").value("0.000000"))
                .andExpect(jsonPath("$.questionsThisMonth").value(0));
    }

    /** Step 7e: this month's spend is every kind since the first at local midnight; questions are the TURN rows. */
    @Test
    void thisMonthSumsEveryKindSinceTheFirstAndCountsTurnsAsQuestions() throws Exception {
        ledgerRow("TURN", "0.010000", LocalDateTime.of(2026, 10, 1, 0, 0));
        ledgerRow("TURN", "0.020000", LocalDateTime.of(2026, 10, 9, 14, 0));
        ledgerRow("CONVERSATION_SUMMARY", "0.001000", LocalDateTime.of(2026, 10, 9, 14, 1));
        ledgerRow("MONTHLY_SUMMARY", "0.000500", LocalDateTime.of(2026, 10, 15, 8, 0));
        ledgerRow("TURN", "0.300000", LocalDateTime.of(2026, 9, 30, 23, 59, 59));
        ledgerRow("TURN", null, LocalDateTime.of(2026, 10, 12, 10, 0)); // unpriced: a question that cost nothing known

        mvc.perform(get("/api/chat/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-10"))
                .andExpect(jsonPath("$.spentThisMonth").value("0.031500"))
                .andExpect(jsonPath("$.questionsThisMonth").value(3))
                .andExpect(jsonPath("$.spentToday").value("0.000500"));

        clock.set(LocalDateTime.of(2026, 11, 1, 0, 0));
        mvc.perform(get("/api/chat/usage"))
                .andExpect(jsonPath("$.month").value("2026-11"))
                .andExpect(jsonPath("$.spentThisMonth").value("0.000000"))
                .andExpect(jsonPath("$.questionsThisMonth").value(0));
    }

    @Test
    void todaysSpendSumsEveryKindSinceLocalMidnightOnly() throws Exception {
        ledgerRow("TURN", "0.012345", LocalDateTime.of(2026, 10, 15, 0, 0));
        ledgerRow("CONVERSATION_SUMMARY", "0.001000", LocalDateTime.of(2026, 10, 15, 9, 30));
        ledgerRow("MONTHLY_SUMMARY", "0.000655", LocalDateTime.of(2026, 10, 15, 11, 59));
        ledgerRow("TURN", "0.500000", LocalDateTime.of(2026, 10, 14, 23, 59, 59));
        jdbc.update("INSERT INTO chat_usage (kind, model, calls) VALUES ('TURN', 'unpriced', 1)");

        mvc.perform(get("/api/chat/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spentToday").value("0.014000"));
    }

    @Test
    void theSpendStartsAgainAtMidnight() throws Exception {
        ledgerRow("TURN", "0.200000", LocalDateTime.of(2026, 10, 15, 18, 0));
        clock.set(LocalDateTime.of(2026, 10, 15, 23, 59));
        mvc.perform(get("/api/chat/usage")).andExpect(jsonPath("$.spentToday").value("0.200000"));

        clock.set(LocalDateTime.of(2026, 10, 16, 0, 0));
        mvc.perform(get("/api/chat/usage"))
                .andExpect(jsonPath("$.day").value("2026-10-16"))
                .andExpect(jsonPath("$.spentToday").value("0.000000"));
    }
}
