package com.finalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.chat.grounding.AnswerGrounding;
import com.finalysis.chat.outbound.ChatOutboundPayloadTest;
import com.finalysis.chat.outbound.ChatPayloadGuard;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import com.finalysis.support.MutableClock;
import com.finalysis.support.OllamaStub;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Data minimization on the Local path (docs/plans/phase-9.md §3.2, §4.4; SEC-01, SEC-08): a whole chat turn through the
 * API on the injection fixture, with Ollama a stub server on loopback. Redaction applies on every path, Local included,
 * so every request body the stub receives is read field by field and checked as {@code ChatTurnPayloadTest} checks
 * Bedrock's prompts: no account name, institution, last four, email, hostile description, canary or id; the question
 * went out redacted and passes the guard; every tool result is the executor's checked JSON. Local's turn costs 0.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(OllamaTurnPayloadTest.Clock.class)
// every tool's result whole in one round is just over the default input cap (as in ChatTurnPayloadTest); the context
// window grows with it, since startup refuses one that can't hold the input budget and the output cap (16000 + 1500)
@TestPropertySource(properties = {"finalysis.ai.default-path=LOCAL",
        "finalysis.ai.ollama.models.chat=" + OllamaTurnPayloadTest.MODEL,
        "finalysis.chat.max-input-tokens-per-call=16000",
        "finalysis.ai.ollama.num-ctx=" + OllamaTurnPayloadTest.NUM_CTX})
class OllamaTurnPayloadTest {

    static final String MODEL = "test-ollama-chat";
    static final int NUM_CTX = 17_500;
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
    static class Clock {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    private static final Set<String> BODY_FIELDS = Set.of("model", "messages", "stream", "tools", "options");
    private static final Set<String> MESSAGE_FIELDS = Set.of("role", "content", "tool_calls", "tool_name");

    private final MockMvc mvc;
    private final ImportTestSupport support;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final MutableClock clock;
    private final ChatPayloadGuard guard;

    OllamaTurnPayloadTest(MockMvc mvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper json,
                          MutableClock clock, ChatPayloadGuard guard) {
        this.mvc = mvc;
        this.support = new ImportTestSupport(mvc, accounts, jdbc);
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.guard = guard;
    }

    @BeforeEach
    void load() throws Exception {
        STUB.reset();
        clock.reset();
        new ChatInjectionFixture(support, jdbc, json).load();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_usage");
        support.deleteCreatedAccounts();
    }

    /**
     * Everything a body sends, but the model's own text: its earlier answers and the retry message, which quotes the
     * model's ungrounded figures back to it by design (§5.5).
     */
    private static List<String> sent(JsonNode body) {
        List<String> sent = new ArrayList<>();
        for (JsonNode message : body.get("messages")) {
            String role = message.get("role").stringValue();
            switch (role) {
                case "tool" -> sent.add(message.get("content").stringValue());
                case "assistant" -> message.path("tool_calls").forEach(call -> sent.add(call.toString()));
                default -> {
                    String content = message.get("content").stringValue();
                    if (!content.startsWith(AnswerGrounding.RETRY_PREFIX)) {
                        sent.add(content);
                    }
                }
            }
        }
        return sent;
    }

    @Test
    void aWholeTurnOnLocalSendsNoIdentifier() throws Exception {
        long id = json.readTree(mvc.perform(post("/api/chat/conversations")).andReturn().getResponse()
                .getContentAsString()).get("id").asLong();
        STUB.then(OllamaStub.toolCalls(
                        "get_month_totals", "{\"from\": \"2026-03\", \"to\": \"2026-03\"}",
                        "get_category_totals", "{\"from\": \"2026-03\", \"to\": \"2026-03\"}",
                        "get_account_totals", "{\"from\": \"2026-03\", \"to\": \"2026-03\"}",
                        "get_top_merchants", "{\"from\": \"2026-03\", \"to\": \"2026-03\", \"limit\": 20}"),
                OllamaStub.toolCalls(
                        "get_trends", "{\"month\": \"2026-03\"}",
                        "compare_periods", "{\"measure\": \"SPENDING\", \"a\": {\"month\": \"2026-03\"}, "
                                + "\"b\": {\"month\": \"2026-02\"}}",
                        "list_transactions", "{\"month\": \"2026-03\", \"limit\": 25}",
                        "get_recurring_charges", "{\"status\": \"ALL\", \"limit\": 25}"),
                OllamaStub.text("You spent $999.99 [c1]."), OllamaStub.text("It was $999.99 [c1]."));

        JsonNode response = json.readTree(mvc.perform(post("/api/chat/conversations/{id}/turns", id)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                                Map.of("message", com.finalysis.chat.ChatTurnPayloadTest.QUESTION))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(response.get("answerSource").stringValue()).isEqualTo("FALLBACK");
        assertThat(response.get("tools")).hasSize(8);
        assertThat(response.get("tools").valueStream().map(t -> t.get("status").stringValue())).containsOnly("OK");
        assertThat(response.get("redactions").get("withheldMerchants").asInt()).isPositive();

        List<JsonNode> bodies = STUB.chatBodies();
        assertThat(bodies).hasSize(4);
        for (JsonNode body : bodies) {
            assertThat(OllamaStub.fields(body)).isSubsetOf(BODY_FIELDS);
            assertThat(body.get("model").stringValue()).isEqualTo(MODEL);
            assertThat(body.get("stream").asBoolean()).isFalse();
            assertThat(body.get("options").get("num_ctx").asInt()).as("every round asks for the configured window")
                    .isEqualTo(NUM_CTX);
            body.get("messages").forEach(m -> assertThat(OllamaStub.fields(m)).isSubsetOf(MESSAGE_FIELDS));
        }
        String all = String.join("\n", bodies.stream().flatMap(b -> sent(b).stream()).toList());
        for (String forbidden : List.of(ChatInjectionFixture.NAME, ChatInjectionFixture.INSTITUTION, "Halden",
                ChatInjectionFixture.LAST_FOUR, "jordan", "example.com", "999.99", "\"id\"", "\"accountId\"",
                "\"statementId\"", "\"fingerprint\"", "\"sourceLine\"", "\"key\"", "\"description\"")) {
            assertThat(all).as(forbidden).doesNotContainIgnoringCase(forbidden);
        }
        for (String description : ChatInjectionFixture.hostileDescriptions()) {
            assertThat(all).doesNotContain(description);
        }
        List<JsonNode> results = bodies.getLast().get("messages").valueStream()
                .filter(m -> m.get("role").stringValue().equals("tool")).toList();
        assertThat(results).hasSize(8);
        String resultText = String.join("", results.stream().map(r -> r.get("content").stringValue()).toList());
        assertThat(ChatOutboundPayloadTest.HOSTILE).allSatisfy(word ->
                assertThat(resultText.toUpperCase()).doesNotContain(word));
        results.forEach(r -> assertThat(json.readTree(r.get("content").stringValue()).has("result")).isTrue());

        // the question went out redacted, inside <question>, and passes the guard's text check
        JsonNode question = bodies.getFirst().get("messages").valueStream()
                .filter(m -> m.get("role").stringValue().equals("user")).findFirst().orElseThrow();
        String outbound = json.readTree(question.get("content").stringValue()
                .replaceAll("^<question>|</question>$", "")).stringValue();
        assertThat(outbound).contains("CHECKING-1").contains("March 2026");
        assertThat(guard.allowsText(outbound)).isTrue();

        // Local: the turn is stored on its path, at no cost; the cap and the month's spend don't count it
        assertThat(response.get("usage").get("path").stringValue()).isEqualTo("LOCAL");
        assertThat(response.get("usage").get("estimatedCost").stringValue()).isEqualTo("0.000000");
        assertThat(jdbc.queryForMap("SELECT path, estimated_cost FROM chat_turn"))
                .containsEntry("path", "LOCAL").containsEntry("estimated_cost", new java.math.BigDecimal("0.000000"));
        assertThat(jdbc.queryForList("SELECT DISTINCT path FROM chat_usage", String.class)).containsExactly("LOCAL");
    }
}
