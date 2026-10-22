package com.finalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI off, the default (docs/plans/phase-6.md §10.4, §18 decision 10): conversations still work, and a turn is 503
 * {@code ai-off}, with no model, no call and nothing stored. The conversation keeps no turns. The search index refresh
 * answers {@code OFF}.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ChatAiOffApiTest {

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ObjectProvider<ChatModel> chatModels;

    ChatAiOffApiTest(MockMvc mvc, JdbcTemplate jdbc, JsonMapper json, ObjectProvider<ChatModel> chatModels) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.json = json;
        this.chatModels = chatModels;
    }

    @AfterEach
    void deleteConversations() {
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_usage");
    }

    @Test
    void aTurnIs503AiOffAndNothingIsStored() throws Exception {
        assertThat(chatModels.stream()).isEmpty();
        long id = json.readTree(mvc.perform(post("/api/chat/conversations")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/chat/conversations/{id}/turns", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"How much did I spend in March?\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:finalysis:problem:ai-off"))
                .andExpect(jsonPath("$.title").value("AI is off"))
                .andExpect(jsonPath("$.detail").value("AI is off. Start the backend with the aws profile to use chat."));

        mvc.perform(get("/api/chat/conversations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns").isEmpty())
                .andExpect(jsonPath("$.title").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_turn", Integer.class)).isZero();
    }

    /** §4.2: the search index refresh answers OFF with AI off, and calls and stores nothing. */
    @Test
    void theSearchIndexRefreshIsOffAndMakesNoCall() throws Exception {
        mvc.perform(post("/api/chat/search-index/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFF"))
                .andExpect(jsonPath("$.calls").value(0))
                .andExpect(jsonPath("$.embedded").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM search_embedding", Integer.class)).isZero();
    }

    @Test
    void aTurnOnAnUnknownConversationIsStill503WithAiOff() throws Exception {
        mvc.perform(post("/api/chat/conversations/{id}/turns", 999_999).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\": \"Hello\"}")).andExpect(status().isServiceUnavailable());
    }
}
