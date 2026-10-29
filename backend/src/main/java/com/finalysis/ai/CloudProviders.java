package com.finalysis.ai;

import java.util.Optional;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The Cloud path's providers (docs/plans/phase-9.md §3.2): today's code behind the three interfaces. They wrap the
 * models Spring configures, which the {@code aws} profile makes Bedrock's ({@code application-aws.yml}) and a test makes
 * its fakes; Bedrock's own code (the client's retries, the prompt cache) stays in {@code ai.bedrock}. Depends on no
 * provider class.
 */
@Component
class CloudProviders {

    private final AiProperties ai;
    private final EmbeddingProperties embedding;
    private final ObjectProvider<ChatModel> chatModel;
    private final ObjectProvider<EmbeddingModel> embeddingModel;
    private final ObjectProvider<PromptCache> promptCache;

    CloudProviders(AiProperties ai, EmbeddingProperties embedding, ObjectProvider<ChatModel> chatModel,
                   ObjectProvider<EmbeddingModel> embeddingModel, ObjectProvider<PromptCache> promptCache) {
        this.ai = ai;
        this.embedding = embedding;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.promptCache = promptCache;
    }

    /** Cloud's providers; empty when Cloud isn't available or a model is missing. */
    Optional<ProviderSet> providers() {
        ChatModel chat = chatModel.getIfAvailable();
        EmbeddingModel embed = embeddingModel.getIfAvailable();
        if (!ai.cloudAvailable() || chat == null || embed == null) {
            return Optional.empty();
        }
        return Optional.of(ProviderSet.cloud(ai, embedding.dimensions(), chat, embed, promptCache.getIfAvailable()));
    }
}
