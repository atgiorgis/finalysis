package com.finalysis.ai;

import java.util.List;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

/**
 * {@link TextEmbedder} over the active path's {@link EmbeddingProvider} (docs/plans/phase-9.md §4.1): one text per call
 * (Titan has no batch call), with the provider's model id in the options and its vector length checked. Depends on no
 * provider class.
 *
 * <p>Usage: Spring AI's Titan model reports the input tokens as prompt tokens and fills in 0 when Bedrock gave none;
 * a call that sent a text never used no tokens, so a missing usage, {@link EmptyUsage} or 0 input tokens all mean
 * "not reported" ({@code n/a} in the log).
 */
@Component
class SpringAiTextEmbedder implements TextEmbedder {

    static final String ROLE = "embedding";

    private final ActiveProviders providers;

    SpringAiTextEmbedder(ActiveProviders providers) {
        this.providers = providers;
    }

    @Override
    public boolean enabled() {
        return providers.usable() && providers.embedding() != null;
    }

    @Override
    public String modelId() {
        EmbeddingProvider embedding = providers.embedding();
        return embedding == null ? null : embedding.modelId();
    }

    @Override
    public int dimensions() {
        EmbeddingProvider embedding = providers.embedding();
        return embedding == null ? 0 : embedding.dimensions();
    }

    @Override
    public Embedding embed(String text) {
        EmbeddingProvider provider = providers.embedding();
        if (!providers.usable() || provider == null) {
            throw new IllegalStateException("AI is off");
        }
        EmbeddingModel model = provider.model();
        int dimensions = provider.dimensions();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Nothing to embed");
        }
        String modelId = provider.modelId();
        long start = System.nanoTime();
        EmbeddingResponse response;
        try {
            response = model.call(new EmbeddingRequest(List.of(text), EmbeddingOptions.builder().model(modelId).build()));
        } catch (RuntimeException e) {
            AiCallLog.recordEmbedding(ROLE, modelId, 1, null, millisSince(start), AiCallLog.Outcome.ERROR, e);
            throw e;
        }
        long millis = millisSince(start);
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        Integer input = reported(usage) ? usage.getPromptTokens() : null;
        float[] vector = response == null || response.getResults().size() != 1 || response.getResult() == null
                ? null : response.getResult().getOutput();
        String problem = problem(vector, dimensions);
        if (problem != null) {
            InvalidEmbeddingException invalid = new InvalidEmbeddingException(problem);
            AiCallLog.recordEmbedding(ROLE, modelId, 1, input, millis, AiCallLog.Outcome.INVALID_OUTPUT, invalid);
            throw invalid;
        }
        AiCallLog.recordEmbedding(ROLE, modelId, 1, input, millis, AiCallLog.Outcome.OK, null);
        return new Embedding(vector.clone(), input, millis);
    }

    /** Whether the provider reported usage; see the class comment. */
    static boolean reported(Usage usage) {
        if (usage == null || usage instanceof EmptyUsage) {
            return false;
        }
        Integer input = usage.getPromptTokens();
        return input != null && input > 0;
    }

    private static String problem(float[] vector, int dimensions) {
        if (vector == null) {
            return "The embedding model returned no single vector";
        }
        if (vector.length != dimensions) {
            return "The embedding model returned " + vector.length + " dimensions, expected " + dimensions;
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                return "The embedding model returned a non-finite value";
            }
        }
        return null;
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
