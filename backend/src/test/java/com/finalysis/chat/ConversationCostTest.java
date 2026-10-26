package com.finalysis.chat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.support.ChatIntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A conversation's total cost (Phase 7 step 7e): the sum of its stored turns' estimated costs, in SQL, on the list and
 * the detail, six decimals like a turn's cost. Unpriced turns add nothing; with no priced turn it is null. The ledger
 * (summaries included) is not read: it has no conversation id.
 */
@ChatIntegrationTest
@AutoConfigureMockMvc
class ConversationCostTest {

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;

    ConversationCostTest(MockMvc mvc, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_usage");
    }

    private long conversation(String title) {
        return jdbc.queryForObject("INSERT INTO chat_conversation (title) VALUES (?) RETURNING id", Long.class, title);
    }

    private void turn(long conversation, int seq, BigDecimal cost) {
        jdbc.update("""
                INSERT INTO chat_turn (conversation_id, seq, question, outbound_question, answer, answer_source,
                    grounding_status, alias_map, model, prompt_version, rounds, tool_calls, millis, estimated_cost)
                VALUES (?, ?, 'q', 'q', 'a', 'MODEL', 'NO_FIGURES', '{}', 'm', 'chat-9', 1, 1, 1, ?)""",
                conversation, seq, cost);
    }

    @Test
    void theTotalIsItsTurnsCostsSummed() throws Exception {
        long id = conversation("Groceries vs dining");
        turn(id, 1, new BigDecimal("0.010412"));
        turn(id, 2, new BigDecimal("0.004100"));
        turn(id, 3, new BigDecimal("0.015003"));
        long other = conversation("Other");
        turn(other, 1, new BigDecimal("0.500000"));
        jdbc.update("INSERT INTO chat_usage (kind, model, calls, estimated_cost) "
                + "VALUES ('CONVERSATION_SUMMARY', 'm', 1, 0.100000)");

        mvc.perform(get("/api/chat/conversations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCost").value("0.029515"))
                .andExpect(jsonPath("$.turns.length()").value(3));
        mvc.perform(get("/api/chat/conversations"))
                .andExpect(jsonPath("$.items[?(@.id == %d)].totalCost", id).value("0.029515"))
                .andExpect(jsonPath("$.items[?(@.id == %d)].turns", id).value(3))
                .andExpect(jsonPath("$.items[?(@.id == %d)].totalCost", other).value("0.500000"));
    }

    @Test
    void unpricedTurnsAddNothingAndNoPricedTurnIsNull() throws Exception {
        long mixed = conversation("Mixed");
        turn(mixed, 1, null);
        turn(mixed, 2, new BigDecimal("0.002000"));
        long unpriced = conversation("Unpriced");
        turn(unpriced, 1, null);
        long empty = conversation("Empty");

        mvc.perform(get("/api/chat/conversations/{id}", mixed)).andExpect(jsonPath("$.totalCost").value("0.002000"));
        mvc.perform(get("/api/chat/conversations/{id}", unpriced)).andExpect(jsonPath("$.totalCost").isEmpty());
        mvc.perform(get("/api/chat/conversations/{id}", empty)).andExpect(jsonPath("$.totalCost").isEmpty());
        mvc.perform(get("/api/chat/conversations"))
                .andExpect(jsonPath("$.items[?(@.id == %d)].totalCost", unpriced).value(org.hamcrest.Matchers.contains(
                        org.hamcrest.Matchers.nullValue())));
    }
}
