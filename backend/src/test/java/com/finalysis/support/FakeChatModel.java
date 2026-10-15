package com.finalysis.support;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;

/**
 * A {@link ChatModel} for tests (docs/plans/phase-4.md §10): captures every {@link Prompt} and answers with a scripted
 * text and token counts, or throws. Answers can depend on the prompt ({@link #answering(Function, Integer, Integer)}),
 * and {@link #thenAnswering} queues one-off answers that are used first, one per call, before the standing one. Its
 * default options support native structured output, like Bedrock's and Ollama's, unless built with
 * {@link #withoutNativeStructuredOutput}. Thread-safe enough for one test at a time, as Spring contexts share it.
 */
public class FakeChatModel implements ChatModel {

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final Deque<Function<Prompt, String>> queued = new ConcurrentLinkedDeque<>();
    private final boolean nativeStructuredOutput;
    private volatile Function<Prompt, String> answer = prompt -> "";
    private volatile Integer inputTokens;
    private volatile Integer outputTokens;
    private volatile RuntimeException failure;

    public FakeChatModel() {
        this(true);
    }

    private FakeChatModel(boolean nativeStructuredOutput) {
        this.nativeStructuredOutput = nativeStructuredOutput;
    }

    public static FakeChatModel withoutNativeStructuredOutput() {
        return new FakeChatModel(false);
    }

    public FakeChatModel answering(String text, Integer inputTokens, Integer outputTokens) {
        return answering(prompt -> text, inputTokens, outputTokens);
    }

    /** The standing answer, made from each prompt. */
    public FakeChatModel answering(Function<Prompt, String> answer, Integer inputTokens, Integer outputTokens) {
        this.answer = answer;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.failure = null;
        return this;
    }

    /** One-off answers for the next calls, in order; then the standing answer again. */
    @SafeVarargs
    public final FakeChatModel thenAnswering(Function<Prompt, String>... answers) {
        queued.addAll(List.of(answers));
        return this;
    }

    /** Forgets prompts, queued answers and failures; answers "" with no usage. For a shared context. */
    public FakeChatModel reset() {
        prompts.clear();
        queued.clear();
        return answering("", null, null);
    }

    public FakeChatModel failingWith(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    public List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    /** The base that ChatClient merges a call's options onto, as the real models' {@code getOptions()} are. */
    @Override
    public ChatOptions getOptions() {
        return nativeStructuredOutput ? StructuredOutputChatOptions.builder().build() : ChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        if (failure != null) {
            throw failure;
        }
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
        if (inputTokens != null || outputTokens != null) {
            metadata.usage(new DefaultUsage(inputTokens, outputTokens));
        }
        Function<Prompt, String> next = queued.poll();
        String text = (next != null ? next : answer).apply(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata.build());
    }
}
