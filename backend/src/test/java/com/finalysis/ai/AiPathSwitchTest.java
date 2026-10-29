package com.finalysis.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.support.IntegrationTest;
import com.finalysis.support.OllamaStub;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Switching the path without a restart (docs/plans/phase-9.md §3.1, §4.4) through {@code PUT /api/settings/ai}, with
 * Local and Self-hosted on a stub Ollama server: the status follows the switch; Cloud without the aws profile and
 * Self-hosted without a valid name are refused; an unreachable server or a model that isn't pulled is selected but off,
 * with the reason; a switch waits for running work and gives up with 409; every run's lease checks reachability first;
 * the setting is read at unlock and dropped while locked; a new embedding model gets its search index. The 423 while
 * locked is {@code VaultGateTest}'s, which checks every mapping.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "finalysis.ai.ollama.models.chat=" + AiPathSwitchTest.CHAT_MODEL)
class AiPathSwitchTest {

    static final String CHAT_MODEL = "test-ollama-chat";
    static final OllamaStub STUB = OllamaStub.start(CHAT_MODEL + ":latest", "nomic-embed-text:latest");

    @DynamicPropertySource
    static void stubPort(DynamicPropertyRegistry registry) {
        registry.add("finalysis.ai.ollama.port", STUB::port);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    private final MockMvc mvc;
    private final AiProviders providers;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    AiPathSwitchTest(MockMvc mvc, AiProviders providers, JdbcTemplate jdbc, JsonMapper json) {
        this.mvc = mvc;
        this.providers = providers;
        this.jdbc = jdbc;
        this.json = json;
    }

    @BeforeEach
    void off() {
        STUB.reset();
        STUB.pulled(CHAT_MODEL + ":latest", "nomic-embed-text:latest");
        providers.switchTo(new AiPathSettings.Selection(AiPath.OFF, null, null), Duration.ofSeconds(30));
        jdbc.update("DELETE FROM app_setting WHERE key LIKE 'ai.%'");
        providers.onUnlocked(); // a fresh vault: no stored name or region (the suite's default path, OFF)
        STUB.reset();
    }

    @AfterEach
    void cleanUp() {
        providers.switchTo(new AiPathSettings.Selection(AiPath.OFF, null, null), Duration.ofSeconds(30));
        jdbc.update("DELETE FROM app_setting WHERE key LIKE 'ai.%'");
        jdbc.update("DELETE FROM search_embedding");
        jdbc.update("DELETE FROM chat_conversation");
    }

    private String switchTo(String body, int expected) throws Exception {
        return mvc.perform(put("/api/settings/ai").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void theTestVaultIsOffAndCloudIsNotAChoiceWithoutTheProfile() throws Exception {
        mvc.perform(get("/api/status"))
                .andExpect(jsonPath("$.ai.path").value("OFF"))
                .andExpect(jsonPath("$.ai.available.length()").value(2))
                .andExpect(jsonPath("$.ai.reachable").value(false));
    }

    @Test
    void switchingToLocalReachesItsServerAndTheStatusFollows() throws Exception {
        String answer = switchTo("{\"path\": \"LOCAL\"}", 200);

        assertThat(json.readTree(answer).get("path").stringValue()).isEqualTo("LOCAL");
        mvc.perform(get("/api/status"))
                .andExpect(jsonPath("$.ai.path").value("LOCAL"))
                .andExpect(jsonPath("$.ai.reachable").value(true))
                .andExpect(jsonPath("$.ai.provider").value("ollama"))
                .andExpect(jsonPath("$.ai.models.main").value(CHAT_MODEL))
                .andExpect(jsonPath("$.ai.models.categorization").value(CHAT_MODEL))
                .andExpect(jsonPath("$.ai.models.embedding").value("nomic-embed-text"))
                .andExpect(jsonPath("$.ai.server").doesNotExist())
                .andExpect(jsonPath("$.ai.reason").doesNotExist());
        assertThat(setting("ai.path")).isEqualTo("LOCAL");
        assertThat(STUB.requests()).extracting(OllamaStub.Request::path).contains("/api/version", "/api/tags");
    }

    /** Self-hosted needs its server's name, never a URL; the region is stored and shown nowhere. */
    @Test
    void selfHostedNeedsAValidNameAndShowsOnlyThatName() throws Exception {
        for (String refused : new String[] {"{\"path\": \"SELF_HOSTED\"}",
                "{\"path\": \"SELF_HOSTED\", \"selfhostName\": \"http://evil.example:11434\"}",
                "{\"path\": \"SELF_HOSTED\", \"selfhostName\": \"two words\"}",
                "{\"path\": \"SELF_HOSTED\", \"selfhostName\": \"a..b\"}",
                "{\"path\": \"SELF_HOSTED\", \"selfhostName\": \"user@host\"}",
                "{\"path\": \"SELF_HOSTED\", \"selfhostName\": \"127.0.0.1\", \"selfhostRegion\": \"US_EAST_1\"}"}) {
            assertThat(switchTo(refused, 400)).as(refused).contains("\"errors\"").contains("\"field\"");
        }
        assertThat(setting("ai.path")).as("nothing refused was stored").isNull();

        // the stub stands in for the server: its name is 127.0.0.1 here
        switchTo("{\"path\": \"SELF_HOSTED\", \"selfhostName\": \"127.0.0.1\", \"selfhostRegion\": \"us-east-1\"}", 200);

        String status = mvc.perform(get("/api/status")).andExpect(jsonPath("$.ai.path").value("SELF_HOSTED"))
                .andExpect(jsonPath("$.ai.server").value("127.0.0.1"))
                .andExpect(jsonPath("$.ai.reachable").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(status).doesNotContain("us-east-1").doesNotContain("region").doesNotContain("http://");
        assertThat(setting("ai.selfhost.region")).isEqualTo("us-east-1");
        // the name is kept: Self-hosted again needs no name
        switchTo("{\"path\": \"LOCAL\"}", 200);
        switchTo("{\"path\": \"SELF_HOSTED\"}", 200);
        assertThat(providers.state().selection().selfhostName()).isEqualTo("127.0.0.1");
    }

    @Test
    void cloudAndUnknownPathsAreRefused() throws Exception {
        assertThat(switchTo("{\"path\": \"CLOUD\"}", 400)).contains("isn't available");
        switchTo("{\"path\": \"MARS\"}", 400);
        assertThat(providers.path()).isEqualTo(AiPath.OFF);
    }

    /** A path that can't run is still the user's choice: it is stored, Corgi is off, and the status says why. */
    @Test
    void aModelThatIsNotPulledIsSelectedButOffWithTheReason() throws Exception {
        STUB.pulled("nomic-embed-text:latest");

        switchTo("{\"path\": \"LOCAL\"}", 200);

        mvc.perform(get("/api/status"))
                .andExpect(jsonPath("$.ai.path").value("LOCAL"))
                .andExpect(jsonPath("$.ai.reachable").value(false))
                .andExpect(jsonPath("$.ai.reason").value("model " + CHAT_MODEL + " not pulled"));
        long id = json.readTree(mvc.perform(post("/api/chat/conversations")).andReturn().getResponse()
                .getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/chat/conversations/{id}/turns", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"How much did I spend in March?\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:finalysis:problem:ai-off"))
                .andExpect(jsonPath("$.reason").value("model " + CHAT_MODEL + " not pulled"));
        assertThat(STUB.requests("/api/chat")).isEmpty();
    }

    /** Before each run, the lease checks the path: a model removed since the switch stops the run. */
    @Test
    void eachRunsLeaseChecksReachability() {
        providers.switchTo(new AiPathSettings.Selection(AiPath.LOCAL, null, null), Duration.ofSeconds(5));
        assertThat(providers.usable()).isTrue();

        STUB.pulled("nomic-embed-text:latest");
        try (ActiveProviders.Lease lease = providers.lease()) {
            assertThat(providers.usable()).isFalse();
            assertThat(providers.state().reason()).isEqualTo("model " + CHAT_MODEL + " not pulled");
        }
        STUB.pulled(CHAT_MODEL + ":latest", "nomic-embed-text:latest");
        try (ActiveProviders.Lease lease = providers.lease()) {
            assertThat(providers.usable()).isTrue();
        }
    }

    /** A switch waits for running work, and gives up after its wait with 409 ai-busy; nothing changes then. */
    @Test
    void aSwitchWaitsForARunAndGivesUpWhenItRunsOn() throws Exception {
        providers.switchTo(new AiPathSettings.Selection(AiPath.LOCAL, null, null), Duration.ofSeconds(5));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try (ActiveProviders.Lease lease = providers.lease()) {
                holding.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

        AiPathSettings.Selection off = new AiPathSettings.Selection(AiPath.OFF, null, null);
        assertThatThrownBy(() -> providers.switchTo(off, Duration.ofMillis(200)))
                .isInstanceOf(AiBusyException.class)
                .satisfies(e -> {
                    AiBusyException busy = (AiBusyException) e;
                    assertThat(busy.getStatusCode().value()).isEqualTo(409);
                    assertThat(busy.getBody().getType()).isEqualTo(URI.create("urn:finalysis:problem:ai-busy"));
                    assertThat(busy.getBody().getProperties()).containsEntry("reason", "AI work still running");
                });
        assertThat(providers.path()).as("nothing changed").isEqualTo(AiPath.LOCAL);

        long released = System.nanoTime();
        CompletableFuture<AiProviders.State> waiting = CompletableFuture.supplyAsync(
                () -> providers.switchTo(off, Duration.ofSeconds(10)));
        Thread.sleep(300);
        assertThat(waiting).isNotDone();
        release.countDown();
        assertThat(waiting.get(10, TimeUnit.SECONDS).path()).isEqualTo(AiPath.OFF);
        assertThat(System.nanoTime() - released).isGreaterThanOrEqualTo(Duration.ofMillis(300).toNanos());
        run.get(5, TimeUnit.SECONDS);
    }

    /** Each path's clients are built for its base URL: Local on loopback, Self-hosted by the server's name. */
    @Test
    void theProvidersAreRebuiltForThePathsBaseUrl() {
        assertThat(providers.baseUrl(new AiPathSettings.Selection(AiPath.LOCAL, "ignored", null)))
                .isEqualTo(URI.create("http://127.0.0.1:" + STUB.port()));
        assertThat(providers.baseUrl(new AiPathSettings.Selection(AiPath.SELF_HOSTED, "finalysis-selfhost", null)))
                .isEqualTo(URI.create("http://finalysis-selfhost:" + STUB.port()));
        assertThat(providers.baseUrl(new AiPathSettings.Selection(AiPath.SELF_HOSTED, null, null))).isNull();

        providers.switchTo(new AiPathSettings.Selection(AiPath.LOCAL, null, null), Duration.ofSeconds(5));
        ProviderSet local = providers.providers();
        providers.switchTo(new AiPathSettings.Selection(AiPath.SELF_HOSTED, "127.0.0.1", null), Duration.ofSeconds(5));

        assertThat(providers.providers()).isNotNull().isNotSameAs(local);
    }

    /** The path lives in the vault: read when it opens, gone while it is locked. */
    @Test
    void thePathIsReadAtUnlockAndDroppedWhileLocked() throws Exception {
        jdbc.update("INSERT INTO app_setting (key, value) VALUES ('ai.path', 'LOCAL')");

        providers.onUnlocked();
        assertThat(providers.path()).isEqualTo(AiPath.LOCAL);
        assertThat(providers.usable()).isTrue();

        providers.onLocking();
        assertThat(providers.path()).isNull();
        assertThat(providers.usable()).isFalse();
        mvc.perform(get("/api/status")).andExpect(jsonPath("$.ai.path").doesNotExist())
                .andExpect(jsonPath("$.ai.reason").value("Vault locked"));

        jdbc.update("DELETE FROM app_setting WHERE key = 'ai.path'");
        providers.onUnlocked();
        assertThat(providers.path()).as("the test vault's default, from the suite's guard").isEqualTo(AiPath.OFF);
    }

    /** A new embedding model gets its search index in the background; other models' vectors are kept. */
    @Test
    void aSwitchToAnotherEmbeddingModelFillsTheSearchIndexForIt() throws Exception {
        jdbc.update("""
                INSERT INTO search_embedding (kind, subject, model, dimensions, input_sha256, embedding)
                VALUES ('CATEGORY', 'Dining', 'amazon.titan-embed-text-v2:0', 3, ?, '[1,0,0]')""", "a".repeat(64));

        switchTo("{\"path\": \"LOCAL\"}", 200);

        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (count("nomic-embed-text") == 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(count("nomic-embed-text")).isPositive();
        assertThat(jdbc.queryForObject("SELECT DISTINCT dimensions FROM search_embedding WHERE model = "
                + "'nomic-embed-text'", Integer.class)).isEqualTo(768);
        assertThat(count("amazon.titan-embed-text-v2:0")).as("the other model's vectors are kept").isOne();
    }

    private int count(String model) {
        return jdbc.queryForObject("SELECT count(*) FROM search_embedding WHERE model = ?", Integer.class, model);
    }

    private String setting(String key) {
        return jdbc.queryForList("SELECT value FROM app_setting WHERE key = ?", String.class, key).stream()
                .findFirst().orElse(null);
    }
}
