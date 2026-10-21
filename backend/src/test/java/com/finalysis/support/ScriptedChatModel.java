package com.finalysis.support;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * A {@link ChatModel} for the chat loop's tests (docs/plans/phase-6.md §14): a queue of steps, one per call, each either
 * tool calls (ids and JSON arguments) or a final text, optionally made from the prompt; with usage or none; cut at the
 * output cap; or a failure. Captures every {@link Prompt}. A call with no step left fails the test. Its options are
 * Spring AI's tool-calling options, like the real models'.
 */
public class ScriptedChatModel implements ChatModel {

    /** One call's answer. */
    public record Step(Function<Prompt, List<Generation>> generations, Integer inputTokens, Integer outputTokens,
                       RuntimeException failure, Consumer<Prompt> before) {

        /** The same step reporting usage. */
        public Step usage(int input, int output) {
            return new Step(generations, input, output, failure, before);
        }

        /** The same step reporting no usage. */
        public Step noUsage() {
            return new Step(generations, null, null, failure, before);
        }

        /** Runs something first (e.g. moves a clock), then answers. */
        public Step after(Consumer<Prompt> action) {
            return new Step(generations, inputTokens, outputTokens, failure, action);
        }
    }

    /** A tool call: name and arguments as JSON text. */
    public record Call(String name, String arguments) {
    }

    public static Call call(String name, String arguments) {
        return new Call(name, arguments);
    }

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final Deque<Step> steps = new ConcurrentLinkedDeque<>();
    private int ids;

    /** Tool calls in one assistant message, with ids {@code tooluse_1}, … across the script. */
    public Step toolCalls(Call... calls) {
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (Call call : calls) {
            toolCalls.add(new AssistantMessage.ToolCall("tooluse_" + (++ids), "function", call.name(),
                    call.arguments()));
        }
        AssistantMessage message = AssistantMessage.builder().content("").toolCalls(toolCalls).build();
        return new Step(prompt -> List.of(generation(message, "tool_use")), 1000, 50, null, null);
    }

    /** A final text. */
    public static Step text(String text) {
        return text(prompt -> text);
    }

    /** A final text made from the prompt (e.g. quoting a figure from a tool result). */
    public static Step text(Function<Prompt, String> text) {
        return new Step(prompt -> List.of(generation(AssistantMessage.builder().content(text.apply(prompt)).build(),
                "end_turn")), 1200, 80, null, null);
    }

    /** A text that stopped at the output cap. */
    public static Step cut(String text) {
        return new Step(prompt -> List.of(generation(AssistantMessage.builder().content(text).build(), "max_tokens")),
                1200, 1500, null, null);
    }

    /** A final text carrying provider content (e.g. reasoning) in its properties, which must round-trip unchanged. */
    public static Step withProperties(String text, Map<String, Object> properties) {
        return new Step(prompt -> List.of(generation(AssistantMessage.builder().content(text).properties(properties)
                .build(), "end_turn")), 1200, 80, null, null);
    }

    /** Tool calls in a message that also carries provider content in its properties. */
    public Step toolCallsWithProperties(Map<String, Object> properties, Call... calls) {
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (Call call : calls) {
            toolCalls.add(new AssistantMessage.ToolCall("tooluse_" + (++ids), "function", call.name(),
                    call.arguments()));
        }
        AssistantMessage message = AssistantMessage.builder().content("").toolCalls(toolCalls).properties(properties)
                .build();
        return new Step(prompt -> List.of(generation(message, "tool_use")), 1000, 50, null, null);
    }

    public static Step failing(RuntimeException failure) {
        return new Step(prompt -> List.of(), null, null, failure, null);
    }

    public ScriptedChatModel then(Step... next) {
        steps.addAll(List.of(next));
        return this;
    }

    /** Forgets prompts and steps. For a shared context. */
    public ScriptedChatModel reset() {
        prompts.clear();
        steps.clear();
        ids = 0;
        return this;
    }

    public List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    public int remaining() {
        return steps.size();
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        Step step = steps.poll();
        if (step == null) {
            throw new AssertionError("the script has no step for call " + prompts.size());
        }
        if (step.before() != null) {
            step.before().accept(prompt);
        }
        if (step.failure() != null) {
            throw step.failure();
        }
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
        if (step.inputTokens() != null) {
            metadata.usage(new DefaultUsage(step.inputTokens(), step.outputTokens()));
        }
        return new ChatResponse(step.generations().apply(prompt), metadata.build());
    }

    private static Generation generation(AssistantMessage message, String finishReason) {
        return new Generation(message, ChatGenerationMetadata.builder().finishReason(finishReason).build());
    }
}
