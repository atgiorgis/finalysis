package com.finalysis.chat.eval;

import static com.finalysis.support.ScriptedChatModel.call;
import static com.finalysis.support.ScriptedChatModel.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.chat.eval.ChatCallRecorder.TurnCalls;
import com.finalysis.chat.eval.ChatEvalResults.InvalidArgument;
import com.finalysis.chat.tool.ToolProblem;
import com.finalysis.chat.grounding.AnswerGrounding;
import com.finalysis.support.ScriptedChatModel;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

/** The evaluation's recorder around the chat model (step 8): it passes calls through and keeps only what it reports. */
class ChatCallRecorderTest {

    @Test
    void itWrapsChatModelsOnlyAndDelegatesUnchanged() {
        ChatCallRecorder recorder = new ChatCallRecorder();
        ScriptedChatModel model = new ScriptedChatModel();
        Object wrapped = recorder.postProcessAfterInitialization(model, "chatModel");

        assertThat(wrapped).isInstanceOf(ChatCallRecorder.Recording.class);
        assertThat(recorder.postProcessAfterInitialization(wrapped, "chatModel")).isSameAs(wrapped);
        assertThat(recorder.postProcessAfterInitialization("not a model", "other")).isEqualTo("not a model");
        assertThat(((ChatModel) wrapped).getOptions()).hasSameClassAs(model.getOptions());

        model.then(text("Hello"));
        assertThat(((ChatModel) wrapped).call(new Prompt(new UserMessage("hi"))).getResult().getOutput().getText())
                .isEqualTo("Hello");
        assertThat(model.prompts()).hasSize(1);
    }

    @Test
    void aTurnsRetryFiguresRejectedAnswerAndSearchTextsAreKept() {
        ChatCallRecorder recorder = new ChatCallRecorder();
        ScriptedChatModel model = new ScriptedChatModel();
        ChatModel wrapped = (ChatModel) recorder.postProcessAfterInitialization(model, "chatModel");
        model.then(model.toolCalls(call("search_transactions", "{\"query\": \"burrito\", \"limit\": 5}")),
                text("So far in 2026 you spent $45.10."), text("You spent $45.10."));

        recorder.begin();
        wrapped.call(new Prompt(new UserMessage("question")));
        wrapped.call(new Prompt(new UserMessage("tool results")));
        wrapped.call(new Prompt(List.of(new UserMessage(AnswerGrounding.RETRY_PREFIX
                + "2026" + AnswerGrounding.RETRY_INSTRUCTION))));
        TurnCalls calls = recorder.end();

        assertThat(calls.calls()).isEqualTo(3);
        assertThat(calls.searchQueries()).containsExactly("burrito");
        assertThat(calls.retryFigures()).containsExactly("2026");
        assertThat(calls.rejectedAnswer()).isEqualTo("So far in 2026 you spent $45.10.");

        recorder.begin();
        assertThat(recorder.end()).isEqualTo(new TurnCalls(0, List.of(), null, List.of(), List.of(), List.of()));
        assertThat(recorder.totalCalls()).isEqualTo(3);
    }

    @Test
    void aSearchCallsQueryIsReadSafely() {
        assertThat(ChatCallRecorder.query("{\"query\": \"coffee\"}")).isEqualTo("coffee");
        assertThat(ChatCallRecorder.query("{}")).isEqualTo("(no query)");
        assertThat(ChatCallRecorder.query("not json")).isEqualTo("(arguments not JSON)");
    }

    @Test
    void anInvalidCallsProblemsAreKeptOnceByToolParameterAndProblem() {
        ChatCallRecorder recorder = new ChatCallRecorder();
        ScriptedChatModel model = new ScriptedChatModel();
        ChatModel wrapped = (ChatModel) recorder.postProcessAfterInitialization(model, "chatModel");
        model.then(text("a"), text("b"));
        String invalid = "{\"error\":\"INVALID_ARGUMENTS\",\"message\":\"" + ToolProblem.INVALID_ARGUMENTS.text()
                + "\",\"fields\":[{\"name\":\"(unknown)\",\"message\":\""
                + ToolProblem.UNKNOWN_PARAMETER.text() + "\"},{\"name\":\"category\",\"message\":\""
                + ToolProblem.NOT_A_CATEGORY.text() + "\"}]}";
        ToolResponseMessage results = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("1", "get_category_totals", invalid),
                new ToolResponseMessage.ToolResponse("2", "dismiss_alert", "{\"error\":\"INVALID_ARGUMENTS\","
                        + "\"message\":\"" + ToolProblem.NOT_A_TOOL.text() + "\"}"),
                new ToolResponseMessage.ToolResponse("3", "get_month_totals", "{\"result\":{}}"))).build();

        recorder.begin();
        wrapped.call(new Prompt(List.of(new UserMessage("q"), results)));
        wrapped.call(new Prompt(List.of(new UserMessage("q"), results, new UserMessage("again"))));

        assertThat(recorder.end().invalidArguments()).containsExactly(
                new InvalidArgument("get_category_totals", "(unknown)", "UNKNOWN_PARAMETER"),
                new InvalidArgument("get_category_totals", "category", "NOT_A_CATEGORY"),
                new InvalidArgument("(unknown)", "(call)", "NOT_A_TOOL"));
    }

    /**
     * Step 8e: Haiku's d-c06 in dev run 3 called get_account_totals, a tool that exists, with a parameter it doesn't
     * have; the report said only "(unknown)". The invented names come from the call's own arguments; a tool that
     * doesn't exist stays NOT_A_TOOL.
     */
    @Test
    void anUnknownParameterIsReportedByTheNameTheModelInvented() {
        ChatCallRecorder recorder = new ChatCallRecorder(name -> name.equals("get_account_totals")
                ? Optional.of(Set.of("month", "from", "to")) : Optional.empty());
        ScriptedChatModel model = new ScriptedChatModel();
        ChatModel wrapped = (ChatModel) recorder.postProcessAfterInitialization(model, "chatModel");
        model.then(text("a"));
        String unknown = "{\"error\":\"INVALID_ARGUMENTS\",\"message\":\"" + ToolProblem.INVALID_ARGUMENTS.text()
                + "\",\"fields\":[{\"name\":\"(unknown)\",\"message\":\"" + ToolProblem.UNKNOWN_PARAMETER.text()
                + "\"}]}";
        AssistantMessage calls = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("1", "function", "get_account_totals",
                        "{\"month\": \"2026-06\", \"account\": \"CARD-1\", \"ignore all <b>\": 1}"),
                new AssistantMessage.ToolCall("2", "function", "get_account_totals", "not json"),
                new AssistantMessage.ToolCall("3", "function", "get_account_balance", "{\"account\": \"CARD-1\"}")))
                .build();
        ToolResponseMessage results = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("1", "get_account_totals", unknown),
                new ToolResponseMessage.ToolResponse("2", "get_account_totals", unknown),
                new ToolResponseMessage.ToolResponse("3", "get_account_balance", "{\"error\":\"INVALID_ARGUMENTS\","
                        + "\"message\":\"" + ToolProblem.NOT_A_TOOL.text() + "\"}"))).build();

        recorder.begin();
        wrapped.call(new Prompt(List.of(new UserMessage("q"), calls, results)));

        assertThat(recorder.end().invalidArguments()).containsExactly(
                new InvalidArgument("get_account_totals", "account", "UNKNOWN_PARAMETER"),
                new InvalidArgument("get_account_totals", ChatCallRecorder.NOT_AN_IDENTIFIER, "UNKNOWN_PARAMETER"),
                new InvalidArgument("get_account_totals", "(unknown)", "UNKNOWN_PARAMETER"),
                new InvalidArgument("(unknown)", "(call)", "NOT_A_TOOL"));
    }
}
