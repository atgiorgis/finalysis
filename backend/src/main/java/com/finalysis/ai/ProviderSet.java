package com.finalysis.ai;

import java.util.Optional;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * The three providers of one path, built together (docs/plans/phase-9.md §3.2).
 *
 * @param provider the provider's short name for the status block: {@code bedrock} or {@code ollama}
 */
public record ProviderSet(String provider, ChatProvider chat, CategorizationProvider categorization,
                          EmbeddingProvider embedding) {

    /**
     * Cloud's three providers over these models (docs/plans/phase-9.md §3.2): the Spring-configured models, Bedrock's under
     * the {@code aws} profile; tests pass their fakes the same way.
     */
    public static ProviderSet cloud(AiProperties ai, int dimensions, ChatModel chat, EmbeddingModel embed,
                                 PromptCache cache) {
        String mainModel = ai.roles().main();
        String categorizationModel = ai.roles().categorization();
        String embeddingModelId = ai.roles().embedding();
        boolean nativeOutput = ai.nativeStructuredOutput();
        return new ProviderSet(ai.provider(),
                new ChatProvider() {
                    @Override
                    public ChatModel model() {
                        return chat;
                    }

                    @Override
                    public String modelId() {
                        return mainModel;
                    }

                    @Override
                    public Optional<PromptCache> promptCache() {
                        return Optional.ofNullable(cache);
                    }
                },
                new CategorizationProvider() {
                    @Override
                    public ChatModel model() {
                        return chat;
                    }

                    @Override
                    public String modelId() {
                        return categorizationModel;
                    }

                    @Override
                    public boolean nativeStructuredOutput() {
                        return nativeOutput;
                    }
                },
                new EmbeddingProvider() {
                    @Override
                    public EmbeddingModel model() {
                        return embed;
                    }

                    @Override
                    public String modelId() {
                        return embeddingModelId;
                    }

                    @Override
                    public int dimensions() {
                        return dimensions;
                    }
                });
    }
}

