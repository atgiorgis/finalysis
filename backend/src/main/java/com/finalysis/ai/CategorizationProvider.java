package com.finalysis.ai;

import org.springframework.ai.chat.model.ChatModel;

/**
 * Categorization's model on the active path (docs/plans/phase-9.md §3.2). Its structured output is the provider's
 * JSON-schema mode (Bedrock's output config, Ollama's {@code format}); the app validates every answer as before.
 */
public interface CategorizationProvider {

    ChatModel model();

    String modelId();

    /** Whether to ask for the provider's native JSON-schema output. */
    boolean nativeStructuredOutput();
}
