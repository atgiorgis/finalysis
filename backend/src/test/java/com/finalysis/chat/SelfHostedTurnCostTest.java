package com.finalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.ai.SelfHostMeter;
import com.finalysis.ai.SelfHostUsage;
import com.finalysis.support.IntegrationTest;
import com.finalysis.support.OllamaStub;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Self-hosted turn's cost line (docs/plans/phase-9.md §4.2, decided at the 9c plan): the turn just answered shows the
 * server's running line from {@link SelfHostMeter} ("running 42 min · ~$0.70"); the same turn read back from history
 * shows the fixed note, since the line isn't stored; chat's usage carries the meter's block. The server is a stub on
 * loopback reached by the name {@code 127.0.0.1}; the meter is a fake, so no EC2 call is made.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(SelfHostedTurnCostTest.Meter.class)
@TestPropertySource(properties = "finalysis.ai.ollama.models.chat=" + SelfHostedTurnCostTest.MODEL)
class SelfHostedTurnCostTest {

    static final String MODEL = "test-ollama-chat";
    static final String LINE = "running 42 min · ~$0.70";
    static final OllamaStub STUB = OllamaStub.start(MODEL + ":latest", "nomic-embed-text:latest");

    @DynamicPropertySource
    static void stubPort(DynamicPropertyRegistry registry) {
        registry.add("finalysis.ai.ollama.port", STUB::port);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Meter {

        @Bean
        @Primary
        SelfHostMeter fakeMeter() {
            return () -> new SelfHostUsage("RUNNING", 42L, "1.006", "0.70", LINE, null);
        }
    }

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    SelfHostedTurnCostTest(MockMvc mvc, JdbcTemplate jdbc, JsonMapper json) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.json = json;
    }

    @AfterEach
    void cleanUp() throws Exception {
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_usage");
        mvc.perform(put("/api/settings/ai").contentType(MediaType.APPLICATION_JSON).content("{\"path\": \"OFF\"}"))
                .andExpect(status().isOk());
        // the path lives in the shared test database: leave none behind for the next context's unlock
        jdbc.update("DELETE FROM app_setting WHERE key LIKE 'ai.%'");
    }

    @Test
    void theLiveTurnShowsTheRunningLineAndHistoryTheFixedNote() throws Exception {
        mvc.perform(put("/api/settings/ai").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\": \"SELF_HOSTED\", \"selfhostName\": \"127.0.0.1\"}"))
                .andExpect(status().isOk());
        long id = json.readTree(mvc.perform(post("/api/chat/conversations")).andReturn().getResponse()
                .getContentAsString()).get("id").asLong();
        STUB.then(OllamaStub.text("I can't tell that from your data."));

        JsonNode turn = json.readTree(mvc.perform(post("/api/chat/conversations/{id}/turns", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("message", "Which month was quietest?"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode usage = turn.get("usage");
        assertThat(usage.get("path").stringValue()).isEqualTo("SELF_HOSTED");
        assertThat(usage.get("costNote").stringValue()).isEqualTo(LINE);
        assertThat(usage.get("estimatedCost").isNull()).as("Self-hosted's cost is time, not tokens").isTrue();

        JsonNode stored = json.readTree(mvc.perform(get("/api/chat/conversations/{id}", id))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(stored.get("turns").get(0).get("usage").get("costNote").stringValue())
                .isEqualTo("Self-hosted cost is the server's running time");

        JsonNode summary = json.readTree(mvc.perform(get("/api/chat/usage")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(summary.get("selfHosted").get("line").stringValue()).isEqualTo(LINE);
        assertThat(summary.get("selfHosted").get("minutesRunning").asLong()).isEqualTo(42);
    }
}
