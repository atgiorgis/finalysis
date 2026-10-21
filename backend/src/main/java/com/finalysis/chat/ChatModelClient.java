package com.finalysis.chat;

import com.finalysis.ai.AiCallLog;
import com.finalysis.ai.AiProperties;
import com.finalysis.ai.ChatRoleOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * One chat round (docs/plans/phase-6.md §10.1): {@code ChatModel.call(Prompt)} with the turn's messages, the
 * {@code main} role's options and the tool definitions, and nothing else: no {@code ChatClient}, so no advisor that
 * could run a tool, log content or add memory. Tool execution is the loop's. A tool call comes back here unexecuted
 * (Spring AI 2.0.1's model only resolves definitions; {@code BedrockToolCallsTest} proves it on the real Converse
 * model), and the callbacks refuse to run anyway ({@link ChatToolCallbacks}).
 *
 * <p>A provider may split one assistant message into several generations (Bedrock Converse puts the text in one and
 * the tool calls in another), so a reply gathers them: every tool call in order, and the texts joined. The message
 * appended to the next round is the one that carries the tool calls, as returned (provider-specific content such as
 * reasoning stays on it), with the joined text.
 *
 * <p>Every call writes one {@link AiCallLog} line with role {@value #ROLE}: tokens, the tool results in the prompt as
 * {@code items}, the outcome ({@code LIMIT} when the answer stopped at the output cap), never content.
 */
@Component
public class ChatModelClient {

    static final String ROLE = "chat";
    public static final String SUMMARY_ROLE = "chat-summary";
    public static final String MONTHLY_ROLE = "summary";

    private final ObjectProvider<ChatModel> chatModel;
    private final AiProperties ai;
    private final ChatRoleOptions options;
    private final ChatToolCallbacks tools;

    public ChatModelClient(ObjectProvider<ChatModel> chatModel, AiProperties ai, ChatRoleOptions options,
                           ChatToolCallbacks tools) {
        this.chatModel = chatModel;
        this.ai = ai;
        this.options = options;
        this.tools = tools;
    }

    /**
     * What one round returned.
     *
     * @param message      the assistant message to append to the next round, unchanged but for the joined text
     * @param toolCalls    the tool calls, in order, with the model's ids; empty for an answer
     * @param text         the answer's text; may be blank
     * @param cut          the model stopped at the output-token cap
     * @param inputTokens  null when the provider didn't report usage
     * @param outputTokens null when the provider didn't report usage
     */
    public record Reply(AssistantMessage message, List<AssistantMessage.ToolCall> toolCalls, String text, boolean cut,
                        Integer inputTokens, Integer outputTokens, long millis) {

        public Reply {
            toolCalls = List.copyOf(toolCalls);
        }

        public boolean usageReported() {
            return inputTokens != null;
        }
    }

    public boolean enabled() {
        return ai.enabled() && chatModel.getIfAvailable() != null;
    }

    public String modelId() {
        return options.modelId();
    }

    /**
     * @param toolResults the tool results in these messages, for the log's {@code items}
     * @throws IllegalStateException if AI is off
     * @throws RuntimeException      whatever the model client throws; the call is logged with the class name first
     */
    public Reply call(List<Message> messages, int maxOutputTokens, int toolResults) {
        return send(messages, maxOutputTokens, tools.callbacks(), ROLE, toolResults);
    }

    /**
     * A call with the main role and no tools: the conversation summary ({@value #SUMMARY_ROLE}) and the monthly summary
     * ({@value #MONTHLY_ROLE}), each with its own role in the AI-06 line (§8).
     *
     * @param role  the log line's role
     * @param items the log line's items: turns folded, or records sent
     */
    public Reply complete(List<Message> messages, int maxOutputTokens, String role, int items) {
        return send(messages, maxOutputTokens, List.of(), role, items);
    }

    private Reply send(List<Message> messages, int maxOutputTokens, List<ToolCallback> callbacks, String role,
                       int toolResults) {
        ChatModel model = chatModel.getIfAvailable();
        if (!ai.enabled() || model == null) {
            throw new IllegalStateException("AI is off");
        }
        String modelId = modelId();
        long start = System.nanoTime();
        ChatResponse response;
        try {
            response = model.call(new Prompt(messages, options.options(model, maxOutputTokens, callbacks)));
        } catch (RuntimeException e) {
            AiCallLog.record(role, modelId, toolResults, null, null, millisSince(start), AiCallLog.Outcome.ERROR, e);
            throw e;
        }
        long millis = millisSince(start);
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        boolean reported = reported(usage);
        Integer input = reported ? usage.getPromptTokens() : null;
        Integer output = reported ? usage.getCompletionTokens() : null;

        List<Generation> generations = response == null ? List.of() : response.getResults().stream()
                .filter(g -> g != null && g.getOutput() != null).toList();
        if (generations.isEmpty()) {
            InvalidReplyException invalid = new InvalidReplyException();
            AiCallLog.record(role, modelId, toolResults, input, output, millis, AiCallLog.Outcome.INVALID_OUTPUT,
                    invalid);
            throw invalid;
        }
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        boolean cut = false;
        AssistantMessage carrier = null;
        for (Generation generation : generations) {
            AssistantMessage message = generation.getOutput();
            calls.addAll(message.getToolCalls());
            if (message.getText() != null && !message.getText().isBlank()) {
                texts.add(message.getText());
            }
            if (carrier == null && message.hasToolCalls()) {
                carrier = message;
            }
            cut |= isCut(generation);
        }
        String text = String.join("\n", texts);
        AssistantMessage message;
        if (generations.size() == 1) {
            message = generations.getFirst().getOutput();
        } else {
            AssistantMessage base = Objects.requireNonNullElse(carrier, generations.getLast().getOutput());
            message = base.mutate().content(text).toolCalls(calls).build();
        }
        AiCallLog.record(role, modelId, toolResults, input, output, millis,
                cut && calls.isEmpty() ? AiCallLog.Outcome.LIMIT : AiCallLog.Outcome.OK, null);
        return new Reply(message, calls, text, cut && calls.isEmpty(), input, output, millis);
    }

    /** The model stopped at the output cap: Converse says {@code max_tokens}, other providers {@code length}. */
    private static boolean isCut(Generation generation) {
        String reason = generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason();
        if (reason == null) {
            return false;
        }
        String r = reason.toLowerCase(Locale.ROOT);
        return r.equals("max_tokens") || r.equals("length");
    }

    /**
     * Whether the provider reported usage. Spring AI stands in {@link EmptyUsage} (zeros) when it has none; a call that
     * sent a prompt never used zero input tokens, so zero input means "not reported", never "free".
     */
    static boolean reported(Usage usage) {
        if (usage == null || usage instanceof EmptyUsage) {
            return false;
        }
        Integer input = usage.getPromptTokens();
        return input != null && input > 0 && usage.getCompletionTokens() != null;
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** The model returned no message at all. Counts nothing, quotes nothing. */
    public static final class InvalidReplyException extends IllegalStateException {

        InvalidReplyException() {
            super("The chat model returned no message");
        }
    }
}
