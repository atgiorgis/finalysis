package com.finalysis.chat.eval;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
 * replaced, and the search texts the model sent. In memory only; nothing is logged. The wrapper delegates every call
 * and the options unchanged, so the app's path is the one measured.
 */
class ChatCallRecorder implements BeanPostProcessor {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What the calls of one turn showed. */
    record TurnCalls(int calls, List<String> retryFigures, String rejectedAnswer, List<String> searchQueries) {
    }

    private int calls;
    private int totalCalls;
    private final List<String> retryFigures = new ArrayList<>();
    private final List<String> searchQueries = new ArrayList<>();
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
        lastAnswer = null;
        rejectedAnswer = null;
    }

    synchronized TurnCalls end() {
        return new TurnCalls(calls, List.copyOf(retryFigures), rejectedAnswer, List.copyOf(searchQueries));
    }

    /** Chat calls since the context started (the retrieval sweep expects none). */
    synchronized int totalCalls() {
        return totalCalls;
    }

    synchronized void sent(Prompt prompt) {
        calls++;
        totalCalls++;
        List<Message> messages = prompt.getInstructions();
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
