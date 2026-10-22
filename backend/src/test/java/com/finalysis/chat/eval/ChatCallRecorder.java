package com.finalysis.chat.eval;

import com.finalysis.chat.eval.ChatEvalResults.InvalidArgument;
import com.finalysis.chat.tool.ToolName;
import com.finalysis.chat.tool.ToolProblem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.config.BeanPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wraps every {@link ChatModel} of the evaluation's context (docs/plans/phase-6.md step 8) so the harness can see what
 * the turn response doesn't carry: the figures a grounding retry listed (for the bare-year count), the answer that retry
 * replaced, the search texts the model sent, and why a tool call was invalid (step 8b: each problem of an
 * {@code INVALID_ARGUMENTS} result as the model read it, by tool, parameter and {@link ToolProblem}). In memory only;
 * nothing is logged. The wrapper delegates every call
 * and the options unchanged, so the app's path is the one measured.
 */
class ChatCallRecorder implements BeanPostProcessor {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What the calls of one turn showed. */
    record TurnCalls(int calls, List<String> retryFigures, String rejectedAnswer, List<String> searchQueries,
                     List<InvalidArgument> invalidArguments) {
    }

    private int calls;
    private int totalCalls;
    private final List<String> retryFigures = new ArrayList<>();
    private final List<String> searchQueries = new ArrayList<>();
    private final List<InvalidArgument> invalidArguments = new ArrayList<>();
    private final Set<String> seenResults = new HashSet<>();
    private String lastAnswer;
    private String rejectedAnswer;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean instanceof ChatModel model && !(bean instanceof Recording) ? new Recording(model, this) : bean;
    }

    /** Starts a turn: what was recorded before is dropped. */
    synchronized void begin() {
        calls = 0;
        retryFigures.clear();
        searchQueries.clear();
        invalidArguments.clear();
        seenResults.clear();
        lastAnswer = null;
        rejectedAnswer = null;
    }

    synchronized TurnCalls end() {
        return new TurnCalls(calls, List.copyOf(retryFigures), rejectedAnswer, List.copyOf(searchQueries),
                List.copyOf(invalidArguments));
    }

    /** Chat calls since the context started (the retrieval sweep expects none). */
    synchronized int totalCalls() {
        return totalCalls;
    }

    synchronized void sent(Prompt prompt) {
        calls++;
        totalCalls++;
        List<Message> messages = prompt.getInstructions();
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage results) {
                for (ToolResponseMessage.ToolResponse result : results.getResponses()) {
                    if (seenResults.add(result.id())) {
                        invalidArguments.addAll(invalid(result.name(), result.responseData()));
                    }
                }
            }
        }
        if (!messages.isEmpty() && messages.getLast() instanceof UserMessage user) {
            List<String> figures = ChatEvalScoring.retryFigures(user.getText());
            if (!figures.isEmpty()) {
                retryFigures.addAll(figures);
                rejectedAnswer = lastAnswer;
            }
        }
    }

    synchronized void received(ChatResponse response) {
        if (response == null) {
            return;
        }
        List<String> texts = new ArrayList<>();
        for (Generation generation : response.getResults()) {
            AssistantMessage message = generation == null ? null : generation.getOutput();
            if (message == null) {
                continue;
            }
            for (AssistantMessage.ToolCall call : message.getToolCalls()) {
                if ("search_transactions".equals(call.name())) {
                    searchQueries.add(query(call.arguments()));
                }
            }
            if (!message.hasToolCalls() && message.getText() != null && !message.getText().isBlank()) {
                texts.add(message.getText());
            }
        }
        if (!texts.isEmpty()) {
            lastAnswer = String.join("\n", texts);
        }
    }

    /**
     * The problems of an {@code INVALID_ARGUMENTS} result: one per field, or one for the whole call. The tool is named
     * only when it is one of the registry's (an invented name is {@code (unknown)}); a message that is none of
     * {@link ToolProblem}'s texts is {@code (other)}.
     */
    static List<InvalidArgument> invalid(String tool, String result) {
        JsonNode node;
        try {
            node = JSON.readTree(result == null ? "{}" : result);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (!"INVALID_ARGUMENTS".equals(node.path("error").asString(""))) {
            return List.of();
        }
        String name = ToolName.fromWire(tool).isPresent() ? tool : "(unknown)";
        List<InvalidArgument> problems = new ArrayList<>();
        for (JsonNode field : node.path("fields")) {
            problems.add(new InvalidArgument(name, field.path("name").asString("(none)"),
                    problem(field.path("message").asString(""))));
        }
        if (problems.isEmpty()) {
            problems.add(new InvalidArgument(name, "(call)", problem(node.path("message").asString(""))));
        }
        return problems;
    }

    private static String problem(String message) {
        return Arrays.stream(ToolProblem.values()).filter(p -> p.text().equals(message)).findFirst()
                .map(Enum::name).orElse("(other)");
    }

    static String query(String arguments) {
        try {
            JsonNode node = JSON.readTree(arguments == null ? "{}" : arguments).get("query");
            return node == null || !node.isString() ? "(no query)" : node.asString();
        } catch (RuntimeException e) {
            return "(arguments not JSON)";
        }
    }

    /** The recording wrapper. */
    static final class Recording implements ChatModel {

        private final ChatModel delegate;
        private final ChatCallRecorder recorder;

        Recording(ChatModel delegate, ChatCallRecorder recorder) {
            this.delegate = delegate;
            this.recorder = recorder;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            recorder.sent(prompt);
            ChatResponse response = delegate.call(prompt);
            recorder.received(response);
            return response;
        }

        /** The wrapped model (a test context reaches its scripted model through it). */
        ChatModel delegate() {
            return delegate;
        }

        @Override
        public ChatOptions getOptions() {
            return delegate.getOptions();
        }
    }
}
