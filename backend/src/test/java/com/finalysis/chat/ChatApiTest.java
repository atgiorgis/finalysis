package com.finalysis.chat;

import static com.finalysis.support.ScriptedChatModel.call;
import static com.finalysis.support.ScriptedChatModel.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.support.ChatIntegrationTest;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.MutableClock;
import com.finalysis.support.ScriptedChatModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The chat endpoints (docs/plans/phase-6.md §10.2, §10.4) with AI on and a scripted model: every endpoint, every 400,
 * 404, 429 and 502, the JSON shapes, and citations re-resolved on read (GONE after their statement is deleted, the last
 * test). The 503 is {@link ChatAiOffApiTest}'s, the 409 {@link ChatServiceTest}'s.
 */
@ChatIntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatApiTest {

    static final String QUESTION = "How much did I spend in March?";
    static final String MARCH = "{\"from\": \"2026-03\", \"to\": \"2026-03\"}";

    private final MockMvc mvc;
    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ScriptedChatModel model;
    private final MutableClock clock;
    private AnalyticsFixture.Loaded loaded;

    ChatApiTest(MockMvc mvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper json, ScriptedChatModel model,
                MutableClock clock) {
        this.mvc = mvc;
        this.support = new ImportTestSupport(mvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, json);
        this.jdbc = jdbc;
        this.json = json;
        this.model = model;
        this.clock = clock;
    }

    @BeforeAll
    void importFixture() throws Exception {
        loaded = fixture.load();
    }

    @AfterAll
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @BeforeEach
    void reset() {
        model.reset();
        clock.reset();
    }

    @AfterEach
    void deleteConversations() {
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_usage");
    }

    private long create() throws Exception {
        MvcResult result = mvc.perform(post("/api/chat/conversations"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/chat/conversations/")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.createdAt").isString())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String turn(String message) {
        return json.writeValueAsString(java.util.Map.of("message", message));
    }

    @Test
    void aTurnAnswersWithTheDocumentedShape() throws Exception {
        long id = create();
        model.then(model.toolCalls(call("get_month_totals", MARCH)),
                text("You spent $1,809.48 in March 2026 [c1]."));

        MvcResult result = mvc.perform(post("/api/chat/conversations/{id}/turns", id)
                        .contentType(MediaType.APPLICATION_JSON).content(turn(QUESTION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(id))
                .andExpect(jsonPath("$.turn").value(1))
                .andExpect(jsonPath("$.answer").value("You spent $1,809.48 in March 2026 [1]."))
                .andExpect(jsonPath("$.answerSource").value("MODEL"))
                .andExpect(jsonPath("$.grounding.status").value("GROUNDED"))
                .andExpect(jsonPath("$.grounding.figuresChecked").value(1))
                .andExpect(jsonPath("$.grounding.retried").value(false))
                .andExpect(jsonPath("$.grounding.ungrounded").value(0))
                .andExpect(jsonPath("$.citations[0].marker").value(1))
                .andExpect(jsonPath("$.citations[0].kind").value("TOTAL"))
                .andExpect(jsonPath("$.citations[0].figures[0].field").value("spending"))
                .andExpect(jsonPath("$.citations[0].figures[0].terms[0].drillDown.path")
                        .value("/api/analytics/transactions"))
                .andExpect(jsonPath("$.tools[0].name").value("get_month_totals"))
                .andExpect(jsonPath("$.tools[0].status").value("OK"))
                .andExpect(jsonPath("$.usage.model").value("test-main-model"))
                .andExpect(jsonPath("$.usage.rounds").value(2))
                .andExpect(jsonPath("$.usage.inputTokens").value(2200))
                .andExpect(jsonPath("$.usage.outputTokens").value(130))
                .andExpect(jsonPath("$.usage.callsWithoutUsage").value(0))
                .andExpect(jsonPath("$.usage.estimatedCost").value("0.002850"))
                .andExpect(jsonPath("$.limitHit").value(nullValue()))
                .andExpect(jsonPath("$.redactions.accountAliases").value(0))
                .andExpect(jsonPath("$.redactions.identifiersRemoved").value(0))
                .andExpect(jsonPath("$.redactions.withheldMerchants").value(0))
                .andExpect(jsonPath("$.redactions.refusedMerchants").value(0))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("conversationId", "turn", "answer",
                "answerSource", "grounding", "citations", "dataNotes", "tools", "usage", "limitHit", "redactions");
        // never a raw tool result or a payload
        assertThat(body.toString()).doesNotContain("\"result\"").doesNotContain("\"ref\"")
                .doesNotContain("savingsRatePercent");
        assertThat(body.get("dataNotes").get(0).has("accountId")).isTrue();
    }

    @Test
    void conversationsAreCreatedListedReadAndDeleted() throws Exception {
        long first = create();
        long second = create();
        clock.advance(java.time.Duration.ofMinutes(1));
        model.then(text("Hello; ask me about your spending."));
        mvc.perform(post("/api/chat/conversations/{id}/turns", first).contentType(MediaType.APPLICATION_JSON)
                .content(turn("Hi there"))).andExpect(status().isOk());

        // newest activity first: the first one had a turn after the second was created
        mvc.perform(get("/api/chat/conversations").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(first))
                .andExpect(jsonPath("$.items[0].title").value("Hi there"))
                .andExpect(jsonPath("$.items[0].turns").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));

        mvc.perform(get("/api/chat/conversations/{id}", first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.turns", hasSize(1)))
                .andExpect(jsonPath("$.turns[0].turn").value(1))
                .andExpect(jsonPath("$.turns[0].question").value("Hi there"))
                .andExpect(jsonPath("$.turns[0].outboundQuestion").value("Hi there"))
                .andExpect(jsonPath("$.turns[0].answer").value("Hello; ask me about your spending."))
                .andExpect(jsonPath("$.turns[0].answerSource").value("MODEL"))
                .andExpect(jsonPath("$.turns[0].grounding.status").value("NO_FIGURES"))
                .andExpect(jsonPath("$.turns[0].usage.rounds").value(1));
        mvc.perform(get("/api/chat/conversations/{id}", second))
                .andExpect(jsonPath("$.title").value(nullValue()))
                .andExpect(jsonPath("$.turns", hasSize(0)));

        mvc.perform(delete("/api/chat/conversations/{id}", first)).andExpect(status().isNoContent());
        mvc.perform(get("/api/chat/conversations/{id}", first)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/chat/conversations/{id}", first)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_turn WHERE conversation_id = ?", Integer.class,
                first)).isZero();
    }

    @Test
    void unknownConversationsAre404() throws Exception {
        mvc.perform(get("/api/chat/conversations/{id}", 999_999)).andExpect(status().isNotFound());
        mvc.perform(post("/api/chat/conversations/{id}/turns", 999_999).contentType(MediaType.APPLICATION_JSON)
                .content(turn(QUESTION))).andExpect(status().isNotFound());
        assertThat(model.prompts()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"message\": \"\"}", "{\"message\": \"   \"}", "{}", "{\"message\": null}",
            "{\"message\": \"hi\", \"model\": \"x\"}"})
    void aBadTurnBodyIs400AndNothingIsCalledOrStored(String body) throws Exception {
        long id = create();

        mvc.perform(post("/api/chat/conversations/{id}/turns", id).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isBadRequest());

        assertThat(model.prompts()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_turn", Integer.class)).isZero();
    }

    @Test
    void aQuestionOverAThousandCharactersIs400() throws Exception {
        long id = create();
        mvc.perform(post("/api/chat/conversations/{id}/turns", id).contentType(MediaType.APPLICATION_JSON)
                        .content(turn("a".repeat(1001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("message"));
    }

    @Test
    void aQuestionThatIsOnlyIdentifiersIs400AndNothingIsSent() throws Exception {
        long id = create();
        mvc.perform(post("/api/chat/conversations/{id}/turns", id).contentType(MediaType.APPLICATION_JSON)
                        .content(turn("someone@example.com 5551234567")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("message"))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("example.com"))));
        assertThat(model.prompts()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=x"})
    void badPagingIs400(String query) throws Exception {
        String[] parts = query.split("=");
        mvc.perform(get("/api/chat/conversations").param(parts[0], parts[1])).andExpect(status().isBadRequest());
    }

    @Test
    void theDailyBudgetIs429WithItsResetTime() throws Exception {
        long id = create();
        jdbc.update("INSERT INTO chat_usage (kind, model, calls, estimated_cost, created_at) "
                + "VALUES ('TURN', 'm', 1, 1.000000, ?)", java.sql.Timestamp.from(clock.instant()));

        mvc.perform(post("/api/chat/conversations/{id}/turns", id).contentType(MediaType.APPLICATION_JSON)
                        .content(turn(QUESTION)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("urn:finalysis:problem:chat-budget"))
                .andExpect(jsonPath("$.resetsAt").value(startsWith("2026-10-16T00:00")))
                .andExpect(jsonPath("$.detail").value("Today's chat budget is spent. It resets at local midnight."));
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    void aFailedModelCallIs502AndTheProblemSaysNothingAboutIt() throws Exception {
        long id = create();
        model.then(ScriptedChatModel.failing(new IllegalStateException("synthetic " + QUESTION)));

        MvcResult result = mvc.perform(post("/api/chat/conversations/{id}/turns", id)
                        .contentType(MediaType.APPLICATION_JSON).content(turn(QUESTION)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:finalysis:problem:ai-unavailable"))
                .andExpect(jsonPath("$.turn").value(1))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("synthetic").doesNotContain("March");
        mvc.perform(get("/api/chat/conversations/{id}", id))
                .andExpect(jsonPath("$.turns[0].answerSource").value("ERROR"))
                .andExpect(jsonPath("$.turns[0].answer").value(nullValue()))
                .andExpect(jsonPath("$.turns[0].grounding").value(nullValue()));
    }

    /** Last: it deletes a fixture statement. Citations are re-resolved on every read. */
    @Test
    @Order(Integer.MAX_VALUE)
    void storedCitationsAreReResolvedAndGoneAfterTheirStatementIsDeleted() throws Exception {
        long id = create();
        model.then(model.toolCalls(call("list_transactions", "{\"month\": \"2026-03\", \"limit\": 1}")),
                text("Your latest March transaction is this one [t1]."));
        mvc.perform(post("/api/chat/conversations/{id}/turns", id).contentType(MediaType.APPLICATION_JSON)
                        .content(turn("What was my latest purchase in March?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[0].kind").value("TRANSACTION"))
                .andExpect(jsonPath("$.citations[0].txn.status").value("FOUND"));

        MvcResult read = mvc.perform(get("/api/chat/conversations/{id}", id))
                .andExpect(jsonPath("$.turns[0].citations[0].kind").value("TRANSACTION"))
                .andExpect(jsonPath("$.turns[0].citations[0].txn.status").value("FOUND"))
                .andReturn();
        long statement = json.readTree(read.getResponse().getContentAsString()).get("turns").get(0).get("citations")
                .get(0).get("txn").get("statement").get("id").asLong();
        assertThat(loaded.statements()).containsValue(statement);

        mvc.perform(delete("/api/statements/{id}", statement)).andExpect(status().is2xxSuccessful());

        mvc.perform(get("/api/chat/conversations/{id}", id))
                .andExpect(jsonPath("$.turns[0].citations[0].txn.status").value("GONE"))
                .andExpect(jsonPath("$.turns[0].citations[0].txn.fingerprint").isString())
                .andExpect(jsonPath("$.turns[0].citations[0].txn.sourceLine").value(nullValue()));
    }
}
