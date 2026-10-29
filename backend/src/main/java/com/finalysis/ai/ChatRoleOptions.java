package com.finalysis.ai;

import java.util.List;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * The {@code main} role's options for one chat call (docs/plans/phase-6.md §2.3, §10.1): the role's model id, the
 * output cap, the tool definitions, and a temperature only when {@code finalysis.ai.chat.temperature} is set.
 *
 * <p>Built on the model's own options ({@code ChatModel.getOptions().mutate()}), as {@code ChatClient} does: a provider
 * may require its own options type (Bedrock Converse casts to {@code BedrockChatOptions}, Ollama to
 * {@code OllamaChatOptions}). The base's temperature (the
 * {@code aws} profile sets 0.0 for categorization) is replaced by the configured one, or cleared, so a model that
 * refuses sampling options gets none ({@link ChatRoleProperties}). Depends on no provider class.
 */
@Component
public class ChatRoleOptions {

    private final ActiveProviders providers;
    private final ChatRoleProperties properties;

    public ChatRoleOptions(ActiveProviders providers, ChatRoleProperties properties) {
        this.providers = providers;
        this.properties = properties;
    }

    /** The active path's chat model; null when AI is off. */
    public String modelId() {
        ChatProvider chat = providers.chat();
        return chat == null ? null : chat.modelId();
    }

    /**
     * @throws IllegalStateException if the model's options can't carry tools
     */
    public ChatOptions options(ChatModel model, int maxOutputTokens, List<ToolCallback> tools) {
        ChatOptions base = model.getOptions();
        ChatOptions.Builder<?> builder = base == null ? ToolCallingChatOptions.builder() : base.mutate();
        builder.combineWith(ToolCallingChatOptions.builder().model(modelId()).maxTokens(maxOutputTokens)
                .toolCallbacks(tools));
        builder.temperature(properties.temperature());
        ChatOptions options = builder.build();
        if (!(options instanceof ToolCallingChatOptions withTools)
                || withTools.getToolCallbacks().size() != tools.size()) {
            throw new IllegalStateException("The chat model's options can't carry tool definitions");
        }
        return options;
    }
}
