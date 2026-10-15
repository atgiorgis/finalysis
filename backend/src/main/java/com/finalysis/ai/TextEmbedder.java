package com.finalysis.ai;

/**
 * Turns one text into a vector with the {@code embedding} role's model (DAT-06). The features use this, not Spring
 * AI's {@code EmbeddingModel} directly, so the call log (AI-06), the usage rules and the vector checks are in one
 * place, and a provider swap stays configuration.
 *
 * <p>The caller decides what may be embedded: this sends exactly the text it is given. Categorization passes only
 * redacted, guard-checked merchant text (SEC-01, SEC-08).
 */
public interface TextEmbedder {

    /** Whether an embedding model is configured: AI is on and a model bean exists. */
    boolean enabled();

    /** The model id stored with every vector; only vectors of the same id are compared. */
    String modelId();

    /** The configured length of every vector. */
    int dimensions();

    /**
     * Embeds one text. Writes one {@link AiCallLog} line, whatever happens.
     *
     * @throws IllegalStateException     if no model is configured
     * @throws InvalidEmbeddingException if the model returned no vector, or one of the wrong length or with a
     *                                   non-finite value
     * @throws RuntimeException          whatever the model client throws (network, throttling, timeout)
     */
    Embedding embed(String text);

    /**
     * A vector and what it cost.
     *
     * @param inputTokens null when the provider didn't report usage (never 0 for a text that was sent)
     */
    record Embedding(float[] vector, Integer inputTokens, long millis) {

        public boolean usageReported() {
            return inputTokens != null;
        }
    }

    /** The model answered, but not with a usable vector. The message has counts only, never the text. */
    final class InvalidEmbeddingException extends IllegalStateException {

        public InvalidEmbeddingException(String message) {
            super(message);
        }
    }
}
