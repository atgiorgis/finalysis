package com.finalysis.ai;

import java.util.Optional;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Chat's model on the active path (docs/plans/phase-9.md §3.2): the model, the id every call names, and the prompt
 * cache when the provider has one (Bedrock only; Ollama has none). Tool execution is the app's own loop on every path.
 */
public interface ChatProvider {

    ChatModel model();

    String modelId();

    Optional<PromptCache> promptCache();
}
