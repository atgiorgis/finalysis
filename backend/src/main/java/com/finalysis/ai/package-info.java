/**
 * AI wiring shared by the features that use a model (docs/plans/phase-4.md §7, docs/plans/phase-9.md §3): the
 * {@link com.finalysis.ai.AiPath} the user chose and its providers ({@link com.finalysis.ai.AiProviders}), the
 * settings fixed at startup ({@link com.finalysis.ai.AiProperties}, {@link com.finalysis.ai.OllamaProperties}), the
 * startup checks, the status block behind the UI's badge and banner, and the token log every call writes (AI-06).
 *
 * <p>Code here and in the features depends only on Spring AI's {@code ChatModel} and {@code EmbeddingModel}, read from
 * the active path's {@link com.finalysis.ai.ProviderSet}. Cloud's models are Spring-configured (the {@code aws}
 * profile turns on Bedrock; {@code ai.bedrock} sets its client); the Ollama paths' are built in {@code ai.ollama},
 * reached through {@link com.finalysis.ai.OllamaClients}.
 */
package com.finalysis.ai;
