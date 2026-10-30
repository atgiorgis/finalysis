package com.finalysis.ai.selfhost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.support.IntegrationTest;
import com.finalysis.support.OllamaStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /api/selfhost} without the {@code aws} profile (docs/plans/phase-9.md §5.5): Start and Stop are disabled with
 * the reason and answer 409 {@code selfhost-unavailable}; Test asks Ollama's version on a name (here a stub on
 * loopback, the suite's guard refuses any other host) and checks the name; chat's usage carries the Self-hosted block.
 * The 423 while locked is {@code VaultGateTest}'s; the phone's login is {@code PhoneAccessTest}'s.
 */
@IntegrationTest
@AutoConfigureMockMvc
class SelfHostApiTest {

    static final OllamaStub STUB = OllamaStub.start("nomic-embed-text:latest");

    @DynamicPropertySource
    static void stubPort(DynamicPropertyRegistry registry) {
        registry.add("finalysis.ai.ollama.port", STUB::port);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;

    SelfHostApiTest(MockMvc mvc, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    /** The path lives in the shared test database: leave none behind for the next context's unlock. */
    @AfterEach
    void forgetThePath() {
        jdbc.update("DELETE FROM app_setting WHERE key LIKE 'ai.%'");
    }

    @BeforeEach
    void off() throws Exception {
        STUB.reset();
        mvc.perform(put("/api/settings/ai").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\": \"OFF\", \"selfhostRegion\": \"us-east-1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void withoutTheAwsProfileTheStatusSaysWhyStartAndStopAreDisabled() throws Exception {
        mvc.perform(get("/api/selfhost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controls").value(false))
                .andExpect(jsonPath("$.reason").value("Start and Stop need the aws profile (Cloud credentials)."))
                .andExpect(jsonPath("$.state").isEmpty());
    }

    @Test
    void startAndStopAnswer409WithTheReason() throws Exception {
        for (String action : new String[] {"/api/selfhost/start", "/api/selfhost/stop"}) {
            mvc.perform(post(action))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:finalysis:problem:selfhost-unavailable"))
                    .andExpect(jsonPath("$.title").value("Self-hosted server unavailable"))
                    .andExpect(jsonPath("$.detail").value("Start and Stop need the aws profile (Cloud credentials)."))
                    .andExpect(jsonPath("$.reason").value("Start and Stop need the aws profile (Cloud credentials)."));
        }
    }

    @Test
    void testAsksOllamasVersionOnTheNameAndNothingElse() throws Exception {
        mvc.perform(post("/api/selfhost/test").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \" 127.0.0.1 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(true))
                .andExpect(jsonPath("$.version").value(OllamaStub.VERSION))
                .andExpect(jsonPath("$.message").value("Reachable, Ollama " + OllamaStub.VERSION));

        assertThat(STUB.requests()).extracting(OllamaStub.Request::method, OllamaStub.Request::path)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("GET", "/api/version"));
    }

    @Test
    void testTakesOnlyAServerNameNeverAUrl() throws Exception {
        for (String body : new String[] {"{}", "{\"name\": \"\"}", "{\"name\": \"http://evil.example:11434\"}",
                "{\"name\": \"two words\"}", "{\"name\": \"user@host\"}", "{\"name\": \"a..b\"}"}) {
            mvc.perform(post("/api/selfhost/test").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("name"));
        }
        assertThat(STUB.requests()).isEmpty();
    }

    @Test
    void chatsUsageCarriesTheSelfHostedBlockWithItsReason() throws Exception {
        mvc.perform(get("/api/chat/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selfHosted.reason").value("Start and Stop need the aws profile (Cloud credentials)."))
                .andExpect(jsonPath("$.selfHosted.state").isEmpty())
                .andExpect(jsonPath("$.selfHosted.line").isEmpty());
    }
}
