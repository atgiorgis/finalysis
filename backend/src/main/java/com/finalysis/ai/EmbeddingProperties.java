package com.finalysis.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Embedding settings ({@code finalysis.ai.embedding.*}). The model id is the {@code embedding} role
 * ({@link AiProperties.Roles#embedding()}).
 *
 * @param dimensions the length of the vectors the embedding model returns. Configured, not asked of the model:
 *                   Spring AI's {@code EmbeddingModel.dimensions()} embeds a test text to find out when it doesn't
 *                   know the model (Titan v2 among them), which would be a cloud call at startup. Every returned
 *                   vector is checked against it.
 */
@ConfigurationProperties("finalysis.ai.embedding")
public record EmbeddingProperties(@DefaultValue("1024") int dimensions) {

    public EmbeddingProperties {
        if (dimensions < 1) {
            throw new IllegalArgumentException("finalysis.ai.embedding.dimensions must be at least 1");
        }
    }
}
