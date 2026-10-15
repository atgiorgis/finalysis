package com.finalysis.categorize;

import com.finalysis.ai.AiCallLog;
import com.finalysis.ai.AiProperties;
import com.finalysis.categorize.CategorizationPromptBuilder.CategorizationPrompt;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Sends one built {@link CategorizationPrompt} to the chat model and returns its structured answer
 * (docs/plans/phase-4.md §4.3). It sends exactly the prompt's two messages: the fixed system prompt and the
 * user message the builder made from redacted, guard-checked data. Nothing else is added (no advisors that log, no
 * tools, no memory).
 *
 * <p>Options: the categorization role's model id, temperature 0, at most 2,000 output tokens, and the provider's
 * native JSON-schema output unless {@code finalysis.ai.native-structured-output} is false. Every call writes one
 * {@link AiCallLog} line, whatever happens. The answer is returned as the model gave it; checking it against the
 * request (§4.4) is the caller's job.
 *
 * <p>Depends only on Spring AI's {@link ChatModel}; which provider is behind it is configuration.
 */
@Component
public class CategorizationModelClient {

    static final String ROLE = "categorization";
    static final double TEMPERATURE = 0.0;
    static final int MAX_OUTPUT_TOKENS = 2000;

    private final ObjectProvider<ChatModel> chatModel;
    private final AiProperties properties;

    public CategorizationModelClient(ObjectProvider<ChatModel> chatModel, AiProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    /**
     * What came back.
     *
     * @param response     the parsed answer, or null when the model's output didn't fit the structure
     * @param inputTokens  null when the provider didn't report it
     * @param outputTokens null when the provider didn't report it
     */
    public record Result(CategorizationResponse response, Integer inputTokens, Integer outputTokens, long millis) {

        public boolean parsed() {
            return response != null;
        }
    }

    /**
     * @throws IllegalStateException if AI is off or the prompt has no items
     * @throws RuntimeException      whatever the model client throws (network, throttling, timeout); the call is
     *                               logged with the exception's class name first
     */
    public Result categorize(CategorizationPrompt prompt) {
        ChatModel model = chatModel.getIfAvailable();
        if (!properties.enabled() || model == null) {
            throw new IllegalStateException("AI is off");
        }
        if (prompt.isEmpty()) {
            throw new IllegalStateException("Nothing to send");
        }
        String modelId = properties.roles().categorization();
        int items = prompt.request().items().size();
        long start = System.nanoTime();
        ResponseEntity<ChatResponse, CategorizationResponse> answer;
        try {
            // As messages, not .system()/.user() text: those are templates, and the JSON has braces.
            ChatClient.ChatClientRequestSpec request = ChatClient.create(model).prompt()
                    .messages(new SystemMessage(prompt.system()), new UserMessage(prompt.user()))
                    .options(ChatOptions.builder().model(modelId).temperature(TEMPERATURE).maxTokens(MAX_OUTPUT_TOKENS));
            if (properties.nativeStructuredOutput()) {
                request = request.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT);
            }
            answer = request.call().responseEntity(new LenientConverter());
        } catch (RuntimeException e) {
            AiCallLog.record(ROLE, modelId, items, null, null, millisSince(start), AiCallLog.Outcome.ERROR, e);
            throw e;
        }
        long millis = millisSince(start);
        Usage usage = answer.response() == null || answer.response().getMetadata() == null
                ? null : answer.response().getMetadata().getUsage();
        Integer input = usage == null ? null : usage.getPromptTokens();
        Integer output = usage == null ? null : usage.getCompletionTokens();
        CategorizationResponse parsed = answer.entity();
        AiCallLog.record(ROLE, modelId, items, input, output, millis,
                parsed == null ? AiCallLog.Outcome.INVALID_OUTPUT : AiCallLog.Outcome.OK, null);
        return new Result(parsed, input, output, millis);
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /**
     * Spring AI's converter (its JSON schema drives native structured output), but output that doesn't parse gives
     * null instead of an exception, so the call's tokens are still logged and the caller decides about a retry. The
     * unparsed text is neither kept nor logged.
     */
    static final class LenientConverter extends BeanOutputConverter<CategorizationResponse> {

        LenientConverter() {
            super(CategorizationResponse.class);
        }

        @Override
        public CategorizationResponse convert(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return super.convert(text);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
