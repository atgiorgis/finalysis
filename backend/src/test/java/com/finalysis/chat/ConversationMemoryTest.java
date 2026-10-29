package com.finalysis.chat;

import static com.finalysis.support.ScriptedChatModel.call;
import static com.finalysis.support.ScriptedChatModel.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.MonthTotals;
import com.finalysis.analytics.MonthlyTotalsService;
import com.finalysis.analytics.SampleDataImport;
import com.finalysis.chat.grounding.GroundingStatus;
import com.finalysis.chat.grounding.SummaryFigureStripper;
import com.finalysis.support.ChatIntegrationTest;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.MutableClock;
import com.finalysis.support.ScriptedChatModel;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
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
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conversation memory (CHT-06, docs/plans/phase-6.md §7.1, §7.2) on the sample data, with a scripted model: the window,
 * folding at the seventh turn outside the summary, the 1,500-token cap, a failed or skipped summary tried again, and the
 * rule that matters most: no figure of an earlier answer reaches the summary or a later prompt, so every figure in a
 * new answer needs a new tool call. The two-turn case is the dev-style equivalent of "how much did i save in august?"
 * then "and what about september?" (the sample data's August and September, read from the months service).
 */
@ChatIntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ConversationMemoryTest {

    static final YearMonth AUGUST = YearMonth.of(2026, 8);
    static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US);

    private final ImportTestSupport support;
    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ChatService chat;
    private final ScriptedChatModel model;
    private final MutableClock clock;
    private final MonthlyTotalsService months;
    private Map<String, Long> accounts;
    private long conversation;

    ConversationMemoryTest(MockMvc mockMvc, AccountRepository accountRepository, JdbcTemplate jdbc, JsonMapper json,
                           ChatService chat, ScriptedChatModel model, MutableClock clock,
                           MonthlyTotalsService months) {
        this.support = new ImportTestSupport(mockMvc, accountRepository, jdbc);
        this.mvc = mockMvc;
        this.jdbc = jdbc;
        this.json = json;
        this.chat = chat;
        this.model = model;
        this.clock = clock;
        this.months = months;
    }

    @BeforeAll
    void importSampleData() throws Exception {
        SampleDataImport sample = new SampleDataImport(support, json);
        accounts = sample.load();
        sample.applyAnswerKey(jdbc, accounts);
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

    // ------------------------------------------------------------------ helpers

    private MonthTotals totals(YearMonth month) {
        return months.months(Optional.of(month), Optional.of(month), Optional.empty()).months().getFirst();
    }

    private static String money(BigDecimal value) {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)).format(value.abs());
    }

    /** Every way a money figure could be written: with and without separators. */
    private static List<String> forms(BigDecimal value) {
        return List.of(money(value), value.abs().toPlainString());
    }

    private static String range(YearMonth month) {
        return "{\"from\": \"" + month + "\", \"to\": \"" + month + "\"}";
    }

    /** One turn: get_month_totals for the month, then an answer quoting its spending, grounded. */
    private ChatTurnResponse spendingTurn(YearMonth month) {
        model.then(model.toolCalls(call("get_month_totals", range(month))),
                text("You spent $" + money(totals(month).spending()) + " in " + month.format(MONTH) + " [c1]."));
        ChatTurnResponse response = chat.ask(conversation, "How much did I spend in " + month.format(MONTH) + "?");
        assertThat(response.grounding().status()).isEqualTo(GroundingStatus.GROUNDED);
        return response;
    }

    /** The texts of a prompt's messages, tool results left out (they are this turn's own). */
    private static String withoutToolResults(Prompt prompt) {
        return prompt.getInstructions().stream().filter(m -> !(m instanceof ToolResponseMessage))
                .map(Message::getText).collect(Collectors.joining("\n"));
    }

    private static List<Message> history(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        int question = messages.size() - 1;
        while (!(messages.get(question) instanceof UserMessage)) {
            question--;
        }
        return messages.subList(ChatPrompt.HISTORY_START, question);
    }

    private Map<String, Object> conversationRow() {
        return jdbc.queryForMap("SELECT summary, summary_through FROM chat_conversation WHERE id = ?", conversation);
    }

    // ------------------------------------------------------------------ the two-turn case

    @Test
    void howMuchDidISaveInAugustThenWhatAboutSeptemberNeedsANewToolCall() {
        MonthTotals august = totals(AUGUST);
        MonthTotals september = totals(SEPTEMBER);
        model.then(model.toolCalls(call("get_month_totals", range(AUGUST))),
                text("You saved $" + money(august.net()) + " in August 2026, a savings rate of "
                        + august.savingsRatePercent() + "% [c1]."));
        ChatTurnResponse first = chat.ask(conversation, "how much did i save in august?");
        assertThat(first.answerSource()).isEqualTo(AnswerSource.MODEL);
        assertThat(first.grounding().status()).isEqualTo(GroundingStatus.GROUNDED);

        int before = model.prompts().size();
        model.then(model.toolCalls(call("get_month_totals", range(SEPTEMBER))),
                text("In September 2026 you saved $" + money(september.net()) + " [c1]."));
        ChatTurnResponse second = chat.ask(conversation, "and what about september?");

        assertThat(second.answerSource()).isEqualTo(AnswerSource.MODEL);
        assertThat(second.grounding().status()).isEqualTo(GroundingStatus.GROUNDED);
        assertThat(second.answer()).contains(money(september.net()));

        Prompt opening = model.prompts().get(before);
        assertThat(history(opening)).extracting(Message::getMessageType)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT);
        assertThat(history(opening).get(0).getText())
                .isEqualTo("<question>\"how much did i save in august?\"</question>");
        assertThat(history(opening).get(1).getText())
                .isEqualTo("You saved [amount] in August 2026, a savings rate of [percent].");
        for (Prompt prompt : model.prompts().subList(before, model.prompts().size())) {
            String sent = withoutToolResults(prompt);
            forms(august.net()).forEach(f -> assertThat(sent).doesNotContain(f));
            assertThat(sent).doesNotContain(august.savingsRatePercent().toPlainString());
        }
        // the stored answer is as shown; only what is sent back loses its figures
        assertThat(jdbc.queryForObject("SELECT answer FROM chat_turn WHERE conversation_id = ? AND seq = 1",
                String.class, conversation)).contains(money(august.net()));
    }

    @Test
    void aFigureFromAnEarlierAnswerNeverGroundsALaterOne() {
        MonthTotals august = totals(AUGUST);
        model.then(model.toolCalls(call("get_month_totals", range(AUGUST))),
                text("You saved $" + money(august.net()) + " in August 2026 [c1]."));
        chat.ask(conversation, "how much did i save in august?");

        // the model repeats August's figure without calling a tool: ungrounded, retried, then the fallback
        model.then(text("As I said, you saved $" + money(august.net()) + " in August."),
                text("You saved $" + money(august.net()) + " in August."));
        ChatTurnResponse again = chat.ask(conversation, "remind me what i saved in august?");

        assertThat(again.answerSource()).isEqualTo(AnswerSource.FALLBACK);
        assertThat(again.grounding().retried()).isTrue();
        assertThat(again.answer()).doesNotContain(money(august.net()));
    }

    @Test
    void theHistoryCarriesOutboundQuestionsNeverTheAccountsName() {
        String name = jdbc.queryForObject("SELECT name FROM account WHERE id = ?", String.class,
                accounts.get("summit-card"));
        model.then(text("I can only answer with figures from your data."));
        ChatTurnResponse first = chat.ask(conversation, "What is my " + name + " for?");
        int before = model.prompts().size();
        model.then(text("Your accounts are listed by alias."));
        chat.ask(conversation, "And the others?");

        String sent = withoutToolResults(model.prompts().get(before));
        assertThat(sent).doesNotContain(name);
        assertThat(history(model.prompts().get(before)).getFirst().getText()).contains(
                jdbc.queryForObject("SELECT outbound_question FROM chat_turn WHERE conversation_id = ? AND seq = 1",
                        String.class, conversation));
        assertThat(first.redactions().accountAliases()).isPositive();
    }

    // ------------------------------------------------------------------ folding

    @Test
    void theSeventhTurnOutsideTheSummaryFoldsTheOldestThreeWithEveryFigureStripped(CapturedOutput output) {
        List<YearMonth> asked = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            asked.add(YearMonth.of(2026, 3 + i));
            spendingTurn(asked.getLast());
        }
        assertThat(conversationRow()).containsEntry("summary_through", 0);
        assertThat(model.prompts()).as("six turns, no summary call").hasSize(12);

        asked.add(SEPTEMBER);
        model.then(model.toolCalls(call("get_month_totals", range(SEPTEMBER))),
                text("You spent $" + money(totals(SEPTEMBER).spending()) + " in September 2026 [c1]."),
                text("The user asked about spending month by month from March to May 2026, about $"
                        + money(totals(asked.getFirst()).spending()) + " in March and 42.5% of income [c1]; "
                        + "CHECKING-1 came up."));
        chat.ask(conversation, "How much did I spend in September 2026?");

        Prompt summarizing = model.prompts().getLast();
        assertThat(summarizing.getInstructions()).extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER);
        String folded = summarizing.getInstructions().get(1).getText();
        assertThat(folded).startsWith("<history>").endsWith("</history>")
                .contains("How much did I spend in March 2026?").contains("How much did I spend in May 2026?")
                .doesNotContain("June 2026");
        Map<String, Object> row = conversationRow();
        assertThat(row).containsEntry("summary_through", 3);
        String summary = (String) row.get("summary");
        assertThat(summary).isEqualTo("The user asked about spending month by month from March to May 2026, about "
                + "[amount] in March and [percent] of income; CHECKING-1 came up.");
        assertThat(SummaryFigureStripper.figureFree(summary)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_usage WHERE kind = 'CONVERSATION_SUMMARY'",
                Integer.class)).isEqualTo(1);
        assertThat(output).contains("ai call role=chat-summary model=test-main-model items=3 inputTokens=1200 "
                + "cacheReadTokens=0 cacheWriteTokens=0 outputTokens=80").contains("chat summary conversation=" + conversation + " folded=3 outcome=FOLDED");

        // the eighth turn: the summary, then the four turns after it, and no earlier figure anywhere
        int before = model.prompts().size();
        spendingTurn(YearMonth.of(2026, 3));
        Prompt opening = model.prompts().get(before);
        List<Message> history = history(opening);
        assertThat(history.getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(history.getFirst().getText()).isEqualTo("<summary>\"" + summary + "\"</summary>");
        assertThat(history.subList(1, history.size())).hasSize(8).extracting(Message::getText)
                .filteredOn(t -> t.startsWith("<question>"))
                .containsExactly("<question>\"How much did I spend in June 2026?\"</question>",
                        "<question>\"How much did I spend in July 2026?\"</question>",
                        "<question>\"How much did I spend in August 2026?\"</question>",
                        "<question>\"How much did I spend in September 2026?\"</question>");
        String sentBefore = withoutToolResults(opening) + "\n" + folded;
        for (YearMonth month : asked) {
            forms(totals(month).spending()).forEach(f -> assertThat(sentBefore).as(month.toString())
                    .doesNotContain(f));
        }
        assertThat(sentBefore).doesNotContain("42.5");
    }

    @Test
    void aFailedSummaryLeavesTheWindowLongerAndIsTriedAgainNextTurn(CapturedOutput output) {
        for (int i = 0; i < 6; i++) {
            spendingTurn(YearMonth.of(2026, 3 + i));
        }
        model.then(model.toolCalls(call("get_month_totals", range(SEPTEMBER))),
                text("You spent $" + money(totals(SEPTEMBER).spending()) + " in September 2026 [c1]."),
                ScriptedChatModel.failing(new IllegalStateException("synthetic throttling")));
        ChatTurnResponse seventh = chat.ask(conversation, "How much did I spend in September 2026?");

        assertThat(seventh.answerSource()).as("the turn itself is fine").isEqualTo(AnswerSource.MODEL);
        assertThat(conversationRow()).containsEntry("summary_through", 0).containsEntry("summary", null);
        assertThat(output).contains("ai call role=chat-summary").contains("outcome=ERROR error=IllegalStateException")
                .contains("folded=3 outcome=FAILED").doesNotContain("synthetic throttling");

        int before = model.prompts().size();
        model.then(model.toolCalls(call("get_month_totals", range(YearMonth.of(2026, 3)))),
                text("You spent $" + money(totals(YearMonth.of(2026, 3)).spending()) + " in March 2026 [c1]."),
                text("Spending questions month by month."));
        chat.ask(conversation, "How much did I spend in March 2026 again?");

        assertThat(history(model.prompts().get(before))).as("all seven earlier turns, no summary").hasSize(14);
        assertThat(conversationRow()).containsEntry("summary_through", 4)
                .containsEntry("summary", "Spending questions month by month.");
    }

    @Test
    void theSummaryCallCountsAgainstTheTurnsTime(CapturedOutput output) {
        for (int i = 0; i < 6; i++) {
            spendingTurn(YearMonth.of(2026, 3 + i));
        }
        model.then(model.toolCalls(call("get_month_totals", range(SEPTEMBER))),
                text("You spent $" + money(totals(SEPTEMBER).spending()) + " in September 2026 [c1].")
                        .after(prompt -> clock.advance(Duration.ofSeconds(50))));
        chat.ask(conversation, "How much did I spend in September 2026?");

        assertThat(conversationRow()).containsEntry("summary_through", 0);
        assertThat(output).contains("folded=3 outcome=SKIPPED_LIMIT limit=TIME");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_usage WHERE kind = 'CONVERSATION_SUMMARY'",
                Integer.class)).isZero();
    }

    @Test
    void historyIsCappedAtFifteenHundredTokensAndLongTurnsFoldSooner() {
        String question = "Tell me about my dining habits please ".repeat(26).strip();
        String answer = "Your dining habits look steady and unremarkable ".repeat(18).strip();
        assertThat(question.length()).isBetween(900, 1000);
        for (int i = 0; i < 2; i++) {
            model.then(text(answer));
            chat.ask(conversation, question);
        }
        assertThat(conversationRow()).as("two long turns fit").containsEntry("summary_through", 0);

        model.then(text(answer), text("The user keeps asking about dining habits."));
        chat.ask(conversation, question);
        assertThat(conversationRow()).as("three don't: the oldest is folded").containsEntry("summary_through", 1);

        int before = model.prompts().size();
        model.then(text("Still steady."));
        chat.ask(conversation, "And now?");
        List<Message> history = history(model.prompts().get(before));
        assertThat(history.getFirst().getText()).startsWith("<summary>");
        assertThat(history.subList(1, history.size())).hasSize(4);
        assertThat(history.stream().mapToInt(m -> TokenEstimate.ofText(m.getText())).sum()).isLessThanOrEqualTo(1_500);
    }

    @Test
    void failedTurnsHaveNoAnswerToSendBack() {
        model.then(ScriptedChatModel.failing(new IllegalStateException("synthetic")));
        try {
            chat.ask(conversation, "How much did I spend in August 2026?");
        } catch (RuntimeException expected) {
            // 502
        }
        int before = model.prompts().size();
        model.then(text("Ask me again."));
        chat.ask(conversation, "Hello?");

        assertThat(history(model.prompts().get(before))).isEmpty();
        assertThat(model.prompts().get(before).getInstructions()).noneMatch(m -> m instanceof AssistantMessage);
    }

    // ------------------------------------------------------------------ delete on demand

    @Test
    void deletingAConversationRemovesItsTurnsToolCallsAndSummaryButNotTheDaysSpend() throws Exception {
        for (int i = 0; i < 6; i++) {
            spendingTurn(YearMonth.of(2026, 3 + i));
        }
        model.then(model.toolCalls(call("get_month_totals", range(SEPTEMBER))),
                text("You spent $" + money(totals(SEPTEMBER).spending()) + " in September 2026 [c1]."),
                text("Spending questions month by month."));
        chat.ask(conversation, "How much did I spend in September 2026?");
        List<Long> turnIds = jdbc.queryForList("SELECT id FROM chat_turn WHERE conversation_id = ?", Long.class,
                conversation);
        assertThat(turnIds).hasSize(7);
        assertThat(conversationRow().get("summary")).isNotNull();
        BigDecimal spent = jdbc.queryForObject("SELECT sum(estimated_cost) FROM chat_usage", BigDecimal.class);

        mvc.perform(delete("/api/chat/conversations/{id}", conversation)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_conversation WHERE id = ?", Integer.class,
                conversation)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_turn WHERE conversation_id = ?", Integer.class,
                conversation)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_tool_call WHERE turn_id IN ("
                + turnIds.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ")", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_conversation WHERE summary IS NOT NULL",
                Integer.class)).isZero();
        // the ledger keeps the day's spend, and has nowhere to keep a question, an answer or a conversation
        assertThat(jdbc.queryForObject("SELECT sum(estimated_cost) FROM chat_usage", BigDecimal.class))
                .isEqualByComparingTo(spent);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = "
                + "'chat_usage'", String.class)).containsExactlyInAnyOrder("id", "kind", "model", "path", "calls",
                "input_tokens", "output_tokens", "cache_read_tokens", "cache_write_tokens", "calls_without_usage",
                "estimated_cost", "created_at");
    }
}
