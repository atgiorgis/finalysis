package com.finalysis.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.ai.AiPath;
import com.finalysis.ai.AiPathSettings;
import com.finalysis.ai.AiProviders;
import com.finalysis.chat.eval.ChatEvalGuards.GitState;
import com.finalysis.support.IntegrationTest;
import com.finalysis.support.OllamaStub;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The search-floor sweep on Ollama (9c-10): run as {@link ChatSearchSweepIT} runs it with
 * {@code -Deval.provider=ollama}, against a stub on loopback (the Local path; a Self-hosted name can't be a stub).
 * The owner's first Self-hosted sweep swept Titan: the context stayed on Cloud. Here the context starts Off, the run
 * switches to the target's path, and every embedding (the index and each query) goes to the stub's {@code /api/embed}
 * with {@code nomic-embed-text}; the report's header names that model, the path and the host. No chat call.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({ChatEvalBeans.class, ChatEvalClock.class, ChatSearchSweepIT.SweepBeans.class})
@TestPropertySource(properties = {
        "finalysis.chat.search.min-similarity=0.30",
        "finalysis.ai.categorization.run-after-import=false",
        "finalysis.ai.ollama.models.chat=" + ChatSearchSweepOllamaTest.MODEL})
class ChatSearchSweepOllamaTest {

    static final String MODEL = "test-ollama-chat";
    static final OllamaStub STUB = OllamaStub.start(MODEL + ":latest", "nomic-embed-text:latest");

    @DynamicPropertySource
    static void stubPort(DynamicPropertyRegistry registry) {
        registry.add("finalysis.ai.ollama.port", STUB::port);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    private final SearchSweepRunner runner;
    private final ChatCallRecorder recorder;
    private final ConfigurableEnvironment environment;
    private final AiProviders providers;
    private final JdbcTemplate jdbc;

    ChatSearchSweepOllamaTest(SearchSweepRunner runner, ChatCallRecorder recorder, ConfigurableEnvironment environment,
                              AiProviders providers, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.runner = runner;
        this.recorder = recorder;
        this.environment = environment;
        this.providers = providers;
    }

    /** The switch is saved in the vault's settings: back to Off, as {@code AiPathSwitchTest} leaves it. */
    @AfterEach
    void offAgain() {
        providers.switchTo(new AiPathSettings.Selection(AiPath.OFF, null, null), Duration.ofSeconds(30));
        jdbc.update("DELETE FROM app_setting WHERE key LIKE 'ai.%'");
    }

    @Test
    void theSweepEmbedsWithTheOllamaPathsModelAndTheHeaderSaysSo() throws Exception {
        assertThat(providers.state().path()).as("the context starts Off, as the IT's does on Ollama")
                .isEqualTo(AiPath.OFF);
        ChatEvalTarget target = ChatEvalTarget.parse("ollama", "http://127.0.0.1:11434");

        SearchSweepReport report = ChatSearchSweepIT.run(runner, recorder, environment, providers, target,
                new GitState("0123456789abcdef", false), Instant.parse("2026-11-03T12:00:00Z"));

        assertThat(report.embeddingModel()).isEqualTo("nomic-embed-text");
        assertThat(report.target().path()).isEqualTo(AiPath.LOCAL);
        assertThat(report.target().ollamaVersion()).isEqualTo(OllamaStub.VERSION);
        assertThat(STUB.requests("/api/embed")).as("the index and the queries embedded by the stub").isNotEmpty()
                .allSatisfy(r -> assertThat(r.json().get("model").stringValue()).isEqualTo("nomic-embed-text"));
        assertThat(STUB.requests("/api/chat")).as("no chat call").isEmpty();
        assertThat(report.sweep().queries()).isNotEmpty();
        String header = report.markdown().substring(0, report.markdown().indexOf("| Floor"));
        assertThat(header)
                .contains("- Path: Local (Ollama " + OllamaStub.VERSION + "), host `127.0.0.1`\n")
                .contains("- Embedding model: `nomic-embed-text`; no chat model was called\n")
                .doesNotContainIgnoringCase("titan");
    }
}
