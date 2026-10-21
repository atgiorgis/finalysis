package com.finalysis.chat;

import static com.finalysis.support.ScriptedChatModel.call;
import static com.finalysis.support.ScriptedChatModel.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.chat.citation.Citation;
import com.finalysis.chat.grounding.GroundingStatus;
import com.finalysis.support.ChatIntegrationTest;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.MutableClock;
import com.finalysis.support.ScriptedChatModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.ErrorResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The chat loop (docs/plans/phase-6.md §1, §5.5, §8, §10.1) on the analytics fixture, with a scripted model: every path
 * a turn can take, the limits, the stored turn and the AI-06 lines. The fixture's March 2026 spending is 1,809.48
 * (sample-data/analytics/expected-month-totals.csv); the scripted answers quote it or invent figures on purpose.
 */
@ChatIntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ChatServiceTest {

    static final String MARCH_TOTALS = "{\"from\": \"2026-03\", \"to\": \"2026-03\"}";
    static final String GROUNDED = "You spent $1,809.48 in March 2026 [c1].";

    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ChatService chat;
    private final ScriptedChatModel model;
    private final MutableClock clock;
    private long conversation;

    ChatServiceTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper json, ChatService chat,
                    ScriptedChatModel model, MutableClock clock) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, json);
        this.jdbc = jdbc;
        this.json = json;
        this.chat = chat;
        this.model = model;
        this.clock = clock;
    }

    @BeforeAll
    void importFixture() throws Exception {
        fixture.load();
    }

    @AfterAll
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @BeforeEach
    void newConversation() {
        model.reset();
        clock.reset();
        conversation = chat.create().id();
    }

    @AfterEach
    void deleteConversations() {
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_usage");
        assertThat(model.remaining()).as("every scripted step was used").isZero();
    }

    private ChatTurnResponse ask(String question) {
        return chat.ask(conversation, question);
    }

    private Map<String, Object> storedTurn() {
        return jdbc.queryForMap("SELECT * FROM chat_turn WHERE conversation_id = ?", conversation);
    }

    private List<Map<String, Object>> storedCalls() {
        return jdbc.queryForList("SELECT c.* FROM chat_tool_call c JOIN chat_turn t ON t.id = c.turn_id "
                + "WHERE t.conversation_id = ? ORDER BY c.seq", conversation);
    }

    /** The tool results of a prompt's last tool-response message, as JSON. */
    private List<JsonNode> lastResults(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        ToolResponseMessage last = (ToolResponseMessage) messages.stream()
                .filter(m -> m instanceof ToolResponseMessage).reduce((a, b) -> b).orElseThrow();
        return last.getResponses().stream().map(r -> json.readTree(r.responseData())).toList();
    }

    // ------------------------------------------------------------------ paths

    @Test
    void anAnswerWithoutToolsIsShownWhenItHasNoFigures() {
        model.then(text("I can only answer questions about your own financial data."));

        ChatTurnResponse response = ask("What's the weather like?");

        assertThat(response.answerSource()).isEqualTo(AnswerSource.MODEL);
        assertThat(response.grounding()).isEqualTo(new ChatTurnResponse.Grounding(GroundingStatus.NO_FIGURES, 0,
                false, 0));
        assertThat(response.tools()).isEmpty();
        assertThat(response.usage().rounds()).isEqualTo(1);
        assertThat(response.turn()).isEqualTo(1);
        assertThat(storedTurn()).containsEntry("answer_source", "MODEL").containsEntry("grounding_status", "NO_FIGURES")
                .containsEntry("rounds", 1).containsEntry("tool_calls", 0)
                .containsEntry("prompt_version", ChatPrompt.VERSION).containsEntry("model", "test-main-model");
    }

    @Test
    void oneToolRoundThenAGroundedAnswerWithItsCitation() {
        model.then(model.toolCalls(call("get_month_totals", MARCH_TOTALS)), text(GROUNDED));

        ChatTurnResponse response = ask("How much did I spend in March?");

        assertThat(response.answerSource()).isEqualTo(AnswerSource.MODEL);
        assertThat(response.answer()).isEqualTo("You spent $1,809.48 in March 2026 [1].");
        assertThat(response.grounding().status()).isEqualTo(GroundingStatus.GROUNDED);
        assertThat(response.grounding().figuresChecked()).isEqualTo(1);
        assertThat(response.citations()).singleElement().isInstanceOfSatisfying(Citation.Total.class, total -> {
            assertThat(total.marker()).isEqualTo(1);
            assertThat(total.figures()).extracting(Citation.CitedFigure::field).containsExactly("spending");
        });
        assertThat(response.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("get_month_totals");
            assertThat(tool.status()).isEqualTo(ToolStatus.OK);
            assertThat(tool.rows()).isPositive();
        });
        // usage: 1000 + 1200 in, 50 + 80 out, at $1 and $5 per million
        assertThat(response.usage()).isEqualTo(new ChatTurnResponse.Usage("test-main-model", 2, 2200, 130, 0,
                "0.002850"));
        assertThat(response.limitHit()).isNull();
        // March 2026 failed reconciliation on the checking account: a data note, local ids only
        assertThat(response.dataNotes()).isNotEmpty();

        assertThat(storedTurn()).containsEntry("answer", response.answer()).containsEntry("rounds", 2)
                .containsEntry("tool_calls", 1).containsEntry("input_tokens", 2200).containsEntry("output_tokens", 130);
        assertThat(storedCalls()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("tool", "get_month_totals").containsEntry("status", "OK")
                    .containsEntry("rows", response.tools().getFirst().rows());
            assertThat(json.readTree(row.get("arguments").toString()).get("from").stringValue()).isEqualTo("2026-03");
        });
        assertThat(jdbc.queryForObject("SELECT title FROM chat_conversation WHERE id = ?", String.class,
                conversation)).isEqualTo("How much did I spend in March?");
    }

    @Test
    void messagesComeInThePlansOrderAndTheContextHasTheClocksToday() {
        model.then(model.toolCalls(call("get_month_totals", MARCH_TOTALS)), text(GROUNDED));

        ask("How much did I spend in March?");

        List<Message> first = model.prompts().getFirst().getInstructions();
        assertThat(first).extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.SYSTEM, MessageType.USER);
        assertThat(first.get(1).getText()).startsWith("<context>").endsWith("</context>")
                .contains("\"today\":\"2026-10-15\"").contains("CHECKING-1");
        assertThat(first.get(2).getText()).isEqualTo("<question>\"How much did I spend in March?\"</question>");
        List<Message> second = model.prompts().get(1).getInstructions();
        assertThat(second).extracting(Message::getMessageType).containsExactly(MessageType.SYSTEM,
                MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL);
        assertThat(second.subList(0, 3)).isEqualTo(first);
    }

    @Test
    void severalRoundsWithParallelCallsAnsweredInOneMessageInOrder() {
        model.then(model.toolCalls(call("get_month_totals", MARCH_TOTALS),
                        call("get_category_totals", MARCH_TOTALS)),
                model.toolCalls(call("compare_periods", "{\"measure\": \"SPENDING\", \"a\": {\"month\": \"2026-03\"}, "
                        + "\"b\": {\"month\": \"2026-02\"}}")),
                text(prompt -> {
                    JsonNode comparison = lastResults(prompt).getFirst().get("result");
                    return "March spending was $" + comparison.get("a").get("value").stringValue() + " ["
                            + comparison.get("ref").stringValue() + "], $" + comparison.get("difference").stringValue()
                            + " more than February.";
                }));

        ChatTurnResponse response = ask("Did I spend more in March than in February?");

        assertThat(response.answerSource()).isEqualTo(AnswerSource.MODEL);
        assertThat(response.grounding().status()).isEqualTo(GroundingStatus.GROUNDED);
        assertThat(response.usage().rounds()).isEqualTo(3);
        assertThat(response.tools()).extracting(ChatTurnResponse.ToolUse::name)
                .containsExactly("get_month_totals", "get_category_totals", "compare_periods");
        Message toolMessage = model.prompts().get(1).getInstructions().getLast();
        assertThat(((ToolResponseMessage) toolMessage).getResponses()).extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("tooluse_1", "tooluse_2");
        assertThat(storedCalls()).extracting(row -> row.get("tool")).containsExactly("get_month_totals",
                "get_category_totals", "compare_periods");
    }

    @Test
    void anUnknownToolGetsAnErrorResultAndIsNeverStoredByName() {
        model.then(model.toolCalls(call("dismiss_alert", "{\"key\": \"x\"}")),
                text("I can't change anything; I can only read your data."));

        ChatTurnResponse response = ask("Dismiss my alerts");

        assertThat(response.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isNull();
            assertThat(tool.status()).isEqualTo(ToolStatus.INVALID_ARGUMENTS);
        });
        assertThat(lastResults(model.prompts().get(1)).getFirst().get("message").stringValue())
                .isEqualTo(com.finalysis.chat.tool.ToolProblem.NOT_A_TOOL.text());
        assertThat(storedCalls()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("tool", "(unknown)").containsEntry("status", "INVALID_ARGUMENTS");
            assertThat(row.get("arguments").toString()).isEqualTo("{}");
        });
        assertThat(response.answerSource()).isEqualTo(AnswerSource.MODEL);
    }

    @Test
    void inventedArgumentsAreAnErrorTheModelCanCorrect() {
        model.then(model.toolCalls(call("get_month_totals", "{\"month\": \"2026-13\", \"accountId\": 5}")),
                model.toolCalls(call("get_month_totals", MARCH_TOTALS)), text(GROUNDED));

        ChatTurnResponse response = ask("How much did I spend in March?");

        assertThat(response.tools()).extracting(ChatTurnResponse.ToolUse::status)
                .containsExactly(ToolStatus.INVALID_ARGUMENTS, ToolStatus.OK);
        JsonNode error = lastResults(model.prompts().get(1)).getFirst();
        assertThat(error.get("error").stringValue()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(error.toString()).doesNotContain("2026-13");
        assertThat(response.grounding().status()).isEqualTo(GroundingStatus.GROUNDED);
        assertThat(response.usage().rounds()).isEqualTo(3);
    }

    @Test
    void anUngroundedAnswerIsRetriedOnceAndTheFixedOneShown() {
        model.then(model.toolCalls(call("get_month_totals", MARCH_TOTALS)),
                text("You spent $1,809.48 in March [c1], $116.21 more than usual."), text(GROUNDED));

        ChatTurnResponse response = ask("How much did I spend in March?");

        assertThat(response.answerSource()).isEqualTo(AnswerSource.MODEL);
        assertThat(response.grounding()).isEqualTo(new ChatTurnResponse.Grounding(GroundingStatus.GROUNDED, 1, true, 0));
        List<Message> retry = model.prompts().get(2).getInstructions();
        assertThat(retry.get(retry.size() - 2).getText()).contains("$116.21 more than usual");
        assertThat(retry.getLast().getMessageType()).isEqualTo(MessageType.USER);
        assertThat(retry.getLast().getText()).startsWith("These figures are not in this turn's tool results: $116.21.");
        assertThat(storedTurn()).containsEntry("retried", true).containsEntry("answer", response.answer());
    }

    @Test
    void ungroundedTwiceGivesTheFallbackAndTheModelsTextIsNeverStored() {
        model.then(model.toolCalls(call("get_month_totals", MARCH_TOTALS)), text("You spent $999.99 [c1]."),
                text("Really, it was $999.99 [c1]."));

        ChatTurnResponse response = ask("How much did I spend in March?");

        assertThat(response.answerSource()).isEqualTo(AnswerSource.FALLBACK);
        assertThat(response.grounding().status()).isEqualTo(GroundingStatus.FALLBACK);
        assertThat(response.grounding().retried()).isTrue();
        assertThat(response.grounding().ungrounded()).isEqualTo(1);
        assertThat(response.answer()).startsWith("I couldn't confirm every figure").contains("spending 1,809.48")
                .doesNotContain("999.99");
        assertThat(response.citations()).isNotEmpty();
        assertThat(response.limitHit()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_turn WHERE answer LIKE '%999.99%'", Integer.class))
                .isZero();
        assertThat(storedTurn()).containsEntry("answer_source", "FALLBACK").containsEntry("ungrounded", 1);
    }

    @Test
    void anAnswerCutAtTheOutputCapIsRetriedAsUngrounded(CapturedOutput output) {
        model.then(model.toolCalls(call("get_month_totals", MARCH_TOTALS)),
                ScriptedChatModel.cut("You spent $1,809.48 in March [c1], and"), text(GROUNDED));

        ChatTurnResponse response = ask("How much did I spend in March?");

        assertThat(response.answerSource()).isEqualTo(AnswerSource.MODEL);
        assertThat(response.grounding().retried()).isTrue();
        assertThat(model.prompts().get(2).getInstructions().getLast().getText()).isEqualTo(TurnLoop.CUT_ANSWER_RETRY);
        assertThat(output).contains("ai call role=chat model=test-main-model items=1 inputTokens=1200 "
                + "outputTokens=1500 ms=").contains("outcome=LIMIT");
    }

    @Test
    void aBlankAnswerGoesToTheFallback() {
        model.then(model.toolCalls(call("get_month_totals", MARCH_TOTALS)), text(" "));

        ChatTurnResponse response = ask("How much did I spend in March?");

        assertThat(response.answerSource()).isEqualTo(AnswerSource.FALLBACK);
        assertThat(response.grounding().retried()).isFalse();
    }

    @Test
    void reasoningContentOnAnAssistantMessageGoesBackUnchanged() {
        Map<String, Object> reasoning = Map.of("reasoningContent", "synthetic reasoning", "signature", "sig-1");
        model.then(model.toolCallsWithProperties(reasoning, call("get_month_totals", MARCH_TOTALS)), text(GROUNDED));

        ask("How much did I spend in March?");

        AssistantMessage sent = (AssistantMessage) model.prompts().get(1).getInstructions().get(3);
        assertThat(sent.getMetadata()).containsAllEntriesOf(reasoning);
        assertThat(sent.getToolCalls()).hasSize(1);
    }

    // ------------------------------------------------------------------ limits

    @Test
    void theRoundLimitEndsInTheFallbackAndTheLastRoundsCallsAreNotRun() {
        for (int i = 0; i < 5; i++) {
            model.then(model.toolCalls(call("get_month_totals", MARCH_TOTALS)));
        }

        ChatTurnResponse response = ask("How much did I spend in March?");

        assertThat(response.limitHit()).isEqualTo(LimitHit.ROUNDS);
        assertThat(response.answerSource()).isEqualTo(AnswerSource.FALLBACK);
        assertThat(response.usage().rounds()).isEqualTo(5);
        assertThat(response.tools()).extracting(ChatTurnResponse.ToolUse::status).containsExactly(ToolStatus.OK,
                ToolStatus.OK, ToolStatus.OK, ToolStatus.OK, ToolStatus.SKIPPED_LIMIT);
        assertThat(storedTurn()).containsEntry("limit_hit", "ROUNDS").containsEntry("tool_calls", 4);
    }

    @Test
    void pastTheToolCallLimitCallsAreSkippedAndTheModelIsToldToAnswer() {
        ScriptedChatModel.Call[] nine = new ScriptedChatModel.Call[9];
        for (int i = 0; i < 9; i++) {
            nine[i] = call("get_month_totals", MARCH_TOTALS);
        }
        model.then(model.toolCalls(nine), text("I read your March totals [c1] and found $1,809.48 of spending."));

        ChatTurnResponse response = ask("How much did I spend in March?");

        assertThat(response.limitHit()).isEqualTo(LimitHit.TOOL_CALLS);
        assertThat(response.tools()).hasSize(9);
        assertThat(response.tools().getLast().status()).isEqualTo(ToolStatus.SKIPPED_LIMIT);
        assertThat(response.tools().subList(0, 8)).allSatisfy(t -> assertThat(t.status()).isEqualTo(ToolStatus.OK));
        assertThat(lastResults(model.prompts().get(1)).getLast().get("message").stringValue())
                .isEqualTo(com.finalysis.chat.tool.ToolProblem.SKIPPED_LIMIT.text());
        assertThat(response.answerSource()).isEqualTo(AnswerSource.MODEL);
        assertThat(storedTurn()).containsEntry("tool_calls", 8);
    }

    @Test
    void anUngroundedAnswerAfterALimitSkipsTheRetry() {
        ScriptedChatModel.Call[] nine = new ScriptedChatModel.Call[9];
        for (int i = 0; i < 9; i++) {
            nine[i] = call("get_month_totals", MARCH_TOTALS);
        }
        model.then(model.toolCalls(nine), text("You spent $5.00."));

        ChatTurnResponse response = ask("How much did I spend in March?");

        assertThat(response.answerSource()).isEqualTo(AnswerSource.FALLBACK);
        assertThat(response.grounding().retried()).isFalse();
        assertThat(response.usage().rounds()).isEqualTo(2);
    }

    @Test
    void theTurnCostLimitStopsBeforeACallThatCouldPassIt() {
        // 30,000 in and 4,000 out at $1 and $5 per million: $0.05 spent, so any further call would pass $0.05
        model.then(model.toolCalls(call("get_month_totals", MARCH_TOTALS)).usage(30_000, 4_000));

        ChatTurnResponse response = ask("How much did I spend in March?");

        assertThat(response.limitHit()).isEqualTo(LimitHit.COST);
        assertThat(response.answerSource()).isEqualTo(AnswerSource.FALLBACK);
        assertThat(response.answer()).contains("spending 1,809.48");
        assertThat(response.usage().estimatedCost()).isEqualTo("0.050000");
        assertThat(response.usage().rounds()).isEqualTo(1);
    }

    @Test
    void theTurnInputTokenLimitStopsTheNextRound() {
        model.then(model.toolCalls(call("get_month_totals", MARCH_TOTALS)).usage(37_000, 10));

        ChatTurnResponse response = ask("How much did I spend in March?");

        assertThat(response.limitHit()).isEqualTo(LimitHit.TOKENS);
        assertThat(response.answerSource()).isEqualTo(AnswerSource.FALLBACK);
    }

    @Test
    void theTimeLimitStopsTheNextRound() {
        model.then(model.toolCalls(call("get_month_totals", MARCH_TOTALS))
                .after(prompt -> clock.advance(Duration.ofSeconds(46))));

        ChatTurnResponse response = ask("How much did I spend in March?");

        assertThat(response.limitHit()).isEqualTo(LimitHit.TIME);
        assertThat(response.answerSource()).isEqualTo(AnswerSource.FALLBACK);
        assertThat(storedTurn()).containsEntry("limit_hit", "TIME");
    }

    @Test
    void aCallWithoutUsageCountsAtItsEstimateAndTheOutputCap() {
        model.then(model.toolCalls(call("get_month_totals", MARCH_TOTALS)).noUsage(), text(GROUNDED).noUsage());

        ChatTurnResponse response = ask("How much did I spend in March?");

        assertThat(response.usage().inputTokens()).isNull();
        assertThat(response.usage().outputTokens()).isNull();
        assertThat(response.usage().callsWithoutUsage()).isEqualTo(2);
        // at least the two output caps: 2 x 1,500 x $5 per million
        assertThat(new java.math.BigDecimal(response.usage().estimatedCost())).isGreaterThan(
                new java.math.BigDecimal("0.015"));
        assertThat(storedTurn()).containsEntry("calls_without_usage", 2).containsEntry("input_tokens", null);
    }

    @Test
    void theDailyCapRefusesBeforeAnyCallAndResetsAtLocalMidnight() {
        jdbc.update("INSERT INTO chat_usage (kind, model, calls, estimated_cost, created_at) "
                + "VALUES ('TURN', 'm', 1, 1.000000, ?)", java.sql.Timestamp.from(clock.instant().minusSeconds(3600)));

        assertThatThrownBy(() -> ask("How much did I spend in March?"))
                .isInstanceOfSatisfying(ErrorResponseException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getBody().getType().toString()).endsWith("chat-budget");
                });
        assertThat(model.prompts()).isEmpty();

        clock.set(MutableClock.SAMPLE_TODAY.plusDays(1).withHour(0).withMinute(1));
        model.then(text("A new day."));
        assertThat(ask("Hello?").answerSource()).isEqualTo(AnswerSource.MODEL);
    }

    @Test
    void oneTurnAtATimePerConversation() throws Exception {
        CountDownLatch inCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        model.then(text("Done.").after(prompt -> {
            inCall.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        CompletableFuture<ChatTurnResponse> first = CompletableFuture.supplyAsync(() -> ask("First?"));
        assertThat(inCall.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> ask("Second?")).isInstanceOfSatisfying(ErrorResponseException.class,
                e -> assertThat(e.getBody().getType().toString()).endsWith("turn-in-progress"));

        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).answerSource()).isEqualTo(AnswerSource.MODEL);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_turn WHERE conversation_id = ?", Integer.class,
                conversation)).isEqualTo(1);
    }

    @Test
    void aFailedCallIsStoredAsAnErrorWithItsClassOnlyAnd502(CapturedOutput output) {
        model.then(model.toolCalls(call("get_month_totals", MARCH_TOTALS)),
                ScriptedChatModel.failing(new IllegalStateException("synthetic failure with How much")));

        assertThatThrownBy(() -> ask("How much did I spend in March?"))
                .isInstanceOfSatisfying(ErrorResponseException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getBody().getProperties()).containsEntry("turn", 1);
                });
        assertThat(storedTurn()).containsEntry("answer_source", "ERROR").containsEntry("answer", null)
                .containsEntry("grounding_status", null).containsEntry("error", "IllegalStateException")
                .containsEntry("rounds", 1).containsEntry("tool_calls", 1);
        assertThat(output).contains("outcome=ERROR error=IllegalStateException")
                .doesNotContain("synthetic failure");
    }

    /** AI-06: one line per model call, with role chat, the tool results in the prompt and the tokens; no content. */
    @Test
    void everyCallWritesItsTokenLine(CapturedOutput output) {
        model.then(model.toolCalls(call("get_month_totals", MARCH_TOTALS)), text(GROUNDED));

        ask("How much did I spend in March?");

        assertThat(output).contains("ai call role=chat model=test-main-model items=0 inputTokens=1000 outputTokens=50")
                .contains("ai call role=chat model=test-main-model items=1 inputTokens=1200 outputTokens=80")
                .contains("chat tool tool=get_month_totals status=OK rows=")
                .contains("chat turn conversation=" + conversation + " turn=1 source=MODEL grounding=GROUNDED");
        assertThat(output.getAll().lines().filter(l -> l.contains("ai call role=chat"))).hasSize(2);
        assertThat(output).doesNotContain("How much did I spend").doesNotContain("1,809.48");
    }
}
