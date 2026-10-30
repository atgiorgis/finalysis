package com.finalysis.chat;

import static com.finalysis.support.ScriptedChatModel.call;
import static com.finalysis.support.ScriptedChatModel.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.chat.outbound.ChatPayloadGuard;
import com.finalysis.support.ChatIntegrationTest;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.MutableClock;
import com.finalysis.support.ScriptedChatModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a whole turn sends, and what it leaves in the log (docs/plans/phase-6.md §12.6, SEC-01, SEC-08, SEC-10), through
 * the API on the injection fixture: a question naming the account by name, institution and last four, with an email;
 * every tool called; an ungrounded answer, the retry and the fallback; then a failed call. Every captured prompt is
 * checked: no account name, institution, last four, email, hostile description, canary or id, the question went out
 * redacted and passes the guard's text check, and every tool result is the executor's JSON. With the app's packages at
 * DEBUG, the log and the problem bodies carry no question, answer, merchant or identifier.
 */
@ChatIntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
// Step 8d: every tool's result whole in one round is now just over the default 12,000 estimated tokens, and the loop
// would cut the oldest (get_month_totals) before it is sent; this test is about every payload going out checked.
// Step 9c-4: the Ollama paths' context window must hold that budget plus the output cap, whatever the path.
@TestPropertySource(properties = {"logging.level.com.finalysis=DEBUG",
        "finalysis.chat.max-input-tokens-per-call=16000", "finalysis.ai.ollama.num-ctx=17500"})
class ChatTurnPayloadTest {

    static final String QUESTION = "How much did I spend on my " + ChatInjectionFixture.NAME + " at "
            + ChatInjectionFixture.INSTITUTION + " ending " + ChatInjectionFixture.LAST_FOUR
            + " in March 2026? Email me at jordan.halden@example.com";

    static final List<ScriptedChatModel.Call> EVERY_TOOL = List.of(
            call("get_month_totals", "{\"from\": \"2026-03\", \"to\": \"2026-03\"}"),
            call("get_category_totals", "{\"from\": \"2026-03\", \"to\": \"2026-03\"}"),
            call("get_account_totals", "{\"from\": \"2026-03\", \"to\": \"2026-03\"}"),
            call("get_top_merchants", "{\"from\": \"2026-03\", \"to\": \"2026-03\", \"limit\": 20}"),
            call("get_trends", "{\"month\": \"2026-03\"}"),
            call("compare_periods", "{\"measure\": \"SPENDING\", \"a\": {\"month\": \"2026-03\"}, "
                    + "\"b\": {\"month\": \"2026-02\"}}"),
            call("list_transactions", "{\"month\": \"2026-03\", \"limit\": 25}"),
            call("get_recurring_charges", "{\"status\": \"ALL\", \"limit\": 25}"));

    private final MockMvc mvc;
    private final ImportTestSupport support;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ScriptedChatModel model;
    private final MutableClock clock;
    private final ChatPayloadGuard guard;

    ChatTurnPayloadTest(MockMvc mvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper json,
                        ScriptedChatModel model, MutableClock clock, ChatPayloadGuard guard) {
        this.mvc = mvc;
        this.support = new ImportTestSupport(mvc, accounts, jdbc);
        this.jdbc = jdbc;
        this.json = json;
        this.model = model;
        this.clock = clock;
        this.guard = guard;
    }

    @BeforeEach
    void load() throws Exception {
        model.reset();
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
     * Everything a prompt sends, but the model's own text: its answers (scripted here, canary included) and the retry
     * message, which quotes the model's ungrounded figures back to it by design (§5.5).
     */
    private static List<String> sent(Prompt prompt) {
        List<String> sent = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            switch (message) {
                case ToolResponseMessage tools -> tools.getResponses().forEach(r -> sent.add(r.responseData()));
                case AssistantMessage assistant -> assistant.getToolCalls().forEach(c -> sent.add(c.arguments()));
                default -> {
                    if (!message.getText().startsWith(com.finalysis.chat.grounding.AnswerGrounding.RETRY_PREFIX)) {
                        sent.add(message.getText());
                    }
                }
            }
        }
        return sent;
    }

    @Test
    void aWholeTurnSendsNoIdentifierAndLogsNoContent(CapturedOutput output) throws Exception {
        long id = json.readTree(mvc.perform(post("/api/chat/conversations")).andReturn().getResponse()
                .getContentAsString()).get("id").asLong();
        model.then(model.toolCalls(EVERY_TOOL.subList(0, 4).toArray(ScriptedChatModel.Call[]::new)),
                model.toolCalls(EVERY_TOOL.subList(4, 8).toArray(ScriptedChatModel.Call[]::new)),
                text("You spent $999.99 [c1]."), text("It was $999.99 [c1]."),
                model.toolCalls(call("get_alerts", "{\"limit\": 20}"), call("get_data_coverage", "{}")),
                text("Here are your alerts and the months your statements cover."));

        String answer = mvc.perform(post("/api/chat/conversations/{id}/turns", id)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                                Map.of("message", QUESTION))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode response = json.readTree(answer);
        assertThat(response.get("answerSource").stringValue()).isEqualTo("FALLBACK");
        assertThat(response.get("grounding").get("retried").asBoolean()).isTrue();
        assertThat(response.get("tools")).hasSize(8);
        assertThat(response.get("tools").valueStream().map(t -> t.get("status").stringValue()))
                .containsOnly("OK");
        assertThat(response.get("redactions").get("accountAliases").asInt()).isPositive();
        assertThat(response.get("redactions").get("identifiersRemoved").asInt()).isPositive();
        assertThat(response.get("redactions").get("withheldMerchants").asInt()).isPositive();

        // the two tools left, in a second turn of the same conversation
        mvc.perform(post("/api/chat/conversations/{id}/turns", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("message", QUESTION))))
                .andExpect(status().isOk());

        List<Prompt> prompts = model.prompts();
        assertThat(prompts).hasSize(6);
        List<String> everything = prompts.stream().flatMap(p -> sent(p).stream()).toList();
        String all = String.join("\n", everything);
        for (String forbidden : List.of(ChatInjectionFixture.NAME, ChatInjectionFixture.INSTITUTION, "Halden",
                ChatInjectionFixture.LAST_FOUR, "jordan", "example.com", "999.99", "\"id\"", "\"accountId\"",
                "\"statementId\"", "\"fingerprint\"", "\"sourceLine\"", "\"key\"", "\"description\"")) {
            assertThat(all).as(forbidden).doesNotContainIgnoringCase(forbidden);
        }
        for (String description : ChatInjectionFixture.hostileDescriptions()) {
            assertThat(all).doesNotContain(description);
        }
        String results = prompts.stream().flatMap(p -> p.getInstructions().stream()).distinct()
                .filter(m -> m instanceof ToolResponseMessage)
                .flatMap(m -> ((ToolResponseMessage) m).getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::responseData).reduce("", String::concat);
        assertThat(com.finalysis.chat.outbound.ChatOutboundPayloadTest.HOSTILE).as("hostile words in tool results")
                .allSatisfy(word -> assertThat(results.toUpperCase()).doesNotContain(word));

        // the question went out redacted, inside <question>, and passes the guard's text check
        Message question = prompts.getFirst().getInstructions().get(2);
        assertThat(question.getMessageType()).isEqualTo(MessageType.USER);
        String outbound = json.readTree(question.getText().replaceAll("^<question>|</question>$", "")).stringValue();
        assertThat(outbound).contains("CHECKING-1").contains("March 2026");
        assertThat(guard.allowsText(outbound)).isTrue();
        // every tool result is a checked result, as the executor writes them
        prompts.stream().flatMap(p -> p.getInstructions().stream()).distinct()
                .filter(m -> m instanceof ToolResponseMessage)
                .flatMap(m -> ((ToolResponseMessage) m).getResponses().stream())
                .forEach(r -> assertThat(json.readTree(r.responseData()).has("result")).as(r.name()).isTrue());

        // a failed call: 502, whose body names nothing
        model.then(ScriptedChatModel.failing(new IllegalStateException(QUESTION)));
        String problem = mvc.perform(post("/api/chat/conversations/{id}/turns", id)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                                Map.of("message", QUESTION))))
                .andExpect(status().isBadGateway())
                .andReturn().getResponse().getContentAsString();

        String log = output.getAll();
        for (String forbidden : List.of(QUESTION, "How much did I spend", ChatInjectionFixture.NAME,
                ChatInjectionFixture.INSTITUTION, ChatInjectionFixture.LAST_FOUR, "example.com", "999.99",
                "I couldn't confirm", response.get("answer").stringValue())) {
            assertThat(log).as("log: " + forbidden).doesNotContain(forbidden);
            assertThat(problem).as("problem: " + forbidden).doesNotContain(forbidden);
        }
        for (String description : ChatInjectionFixture.hostileDescriptions()) {
            assertThat(log).doesNotContain(description);
        }
        assertThat(log).contains("ai call role=chat").contains("chat turn conversation=" + id);
    }
    /**
     * Step 7's two new payloads on the same fixture: a later turn's history (the earlier fallback answer, figures
     * stripped, sent back as an assistant message) and the monthly summary's records. Neither carries an identifier,
     * a hostile description or a hostile word.
     */
    @Test
    void historyAndTheMonthlySummarySendNoIdentifier() throws Exception {
        long id = json.readTree(mvc.perform(post("/api/chat/conversations")).andReturn().getResponse()
                .getContentAsString()).get("id").asLong();
        model.then(model.toolCalls(EVERY_TOOL.get(0), EVERY_TOOL.get(6)), text("You spent $999.99 [c1]."),
                text("It was $999.99 [c1]."), text("Nothing more to add."));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/chat/conversations/{id}/turns", id).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("message", QUESTION))))
                    .andExpect(status().isOk());
        }
        Prompt later = model.prompts().getLast();
        List<Message> history = later.getInstructions().stream().filter(m -> m instanceof AssistantMessage).toList();
        assertThat(history).as("the first turn's fallback, sent back").hasSize(1);
        assertThat(history.getFirst().getText()).contains("[amount]");

        int before = model.prompts().size();
        model.then(text("A month of spending."));
        mvc.perform(post("/api/chat/summaries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"month\": \"2026-03\"}"))
                .andExpect(status().isOk());
        Prompt summary = model.prompts().get(before);

        for (Prompt prompt : List.of(later, summary)) {
            String all = prompt.getInstructions().stream().map(Message::getText).reduce("", (a, b) -> a + "\n" + b);
            for (String forbidden : List.of(ChatInjectionFixture.NAME, ChatInjectionFixture.INSTITUTION, "Halden",
                    ChatInjectionFixture.LAST_FOUR, "jordan", "example.com", "999.99", "\"id\"", "\"accountId\"",
                    "\"statementId\"", "\"fingerprint\"", "\"sourceLine\"", "\"key\"", "\"description\"")) {
                assertThat(all).as(forbidden).doesNotContainIgnoringCase(forbidden);
            }
            for (String description : ChatInjectionFixture.hostileDescriptions()) {
                assertThat(all).doesNotContain(description);
            }
        }
        // the data parts: the answer's lines sent back (without the fallback's own fixed header), and the records
        String lines = history.getFirst().getText().replace("I couldn't confirm every figure in my answer, so here is "
                + "what the data returned:", "");
        assertThat(lines).contains("MERCHANT WITHHELD");
        for (String data : List.of(lines, summary.getInstructions().get(1).getText())) {
            assertThat(com.finalysis.chat.outbound.ChatOutboundPayloadTest.HOSTILE)
                    .allSatisfy(word -> assertThat(data.toUpperCase()).doesNotContain(word));
        }
    }
}
