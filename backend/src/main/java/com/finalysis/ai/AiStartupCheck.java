package com.finalysis.ai;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup when the AI configuration is inconsistent (AI-03, docs/plans/phase-4.md §7), once every bean
 * exists:
 * <ul>
 *   <li>the {@code aws} and {@code local} profiles are both active (cloud and local at once is never meant);</li>
 *   <li>a mode other than OFF has no {@link ChatModel} or {@link EmbeddingModel} to use;</li>
 *   <li>mode OFF has a model anyway, e.g. {@code spring.ai.model.chat} set without a profile. AI is on only when a
 *       profile says so, so that the status banner can't be wrong.</li>
 * </ul>
 */
@Component
class AiStartupCheck implements SmartInitializingSingleton {

    static final String CLOUD_PROFILE = "aws";
    static final String LOCAL_PROFILE = "local";

    private static final Logger log = LoggerFactory.getLogger(AiStartupCheck.class);

    private final Environment environment;
    private final AiProperties properties;
    private final ObjectProvider<ChatModel> chatModels;
    private final ObjectProvider<EmbeddingModel> embeddingModels;

    AiStartupCheck(Environment environment, AiProperties properties, ObjectProvider<ChatModel> chatModels,
                   ObjectProvider<EmbeddingModel> embeddingModels) {
        this.environment = environment;
        this.properties = properties;
        this.chatModels = chatModels;
        this.embeddingModels = embeddingModels;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verify(Arrays.asList(environment.getActiveProfiles()), properties.mode(),
                chatModels.stream().findAny().isPresent(), embeddingModels.stream().findAny().isPresent());
        if (properties.enabled()) {
            log.info("AI mode {} (provider {})", properties.mode(), properties.provider());
        } else {
            log.info("AI mode OFF: rules-only categorization");
        }
    }

    static void verify(List<String> activeProfiles, AiMode mode, boolean hasChatModel, boolean hasEmbeddingModel) {
        if (activeProfiles.contains(CLOUD_PROFILE) && activeProfiles.contains(LOCAL_PROFILE)) {
            throw new IllegalStateException("The '" + CLOUD_PROFILE + "' and '" + LOCAL_PROFILE
                    + "' profiles can't be active together; choose one");
        }
        if (mode != AiMode.OFF && (!hasChatModel || !hasEmbeddingModel)) {
            throw new IllegalStateException("finalysis.ai.mode is " + mode
                    + " but no chat or embedding model is configured; enable AI through its profile");
        }
        if (mode == AiMode.OFF && (hasChatModel || hasEmbeddingModel)) {
            throw new IllegalStateException("A chat or embedding model is configured while finalysis.ai.mode is OFF;"
                    + " AI is enabled only through its profile");
        }
    }
}
