package com.finalysis.support;

import java.util.ArrayList;
import java.util.List;
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
 * text and token counts, or throws. Its default options support native structured output, like Bedrock's and
 * Ollama's, unless built with {@link #withoutNativeStructuredOutput}.
 */
public class FakeChatModel implements ChatModel {

    private final List<Prompt> prompts = new ArrayList<>();
    private final boolean nativeStructuredOutput;
    private Function<Prompt, String> answer = prompt -> "";
    private Integer inputTokens;
    private Integer outputTokens;
    private RuntimeException failure;

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
        this.answer = prompt -> text;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.failure = null;
        return this;
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
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer.apply(prompt)))), metadata.build());
    }
}
