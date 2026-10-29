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
 * Fails startup when the AI configuration is inconsistent (AI-03, docs/plans/phase-4.md §7, docs/plans/phase-9.md
 * §3.1), once every bean exists:
 * <ul>
 *   <li>the {@code aws} and {@code local} profiles are both active (the {@code local} profile is gone: Local is a
 *       setting; activating it is refused with the same message as before);</li>
 *   <li>{@code finalysis.ai.cloud-available} is true but there is no {@link ChatModel} or {@link EmbeddingModel} for
 *       Cloud: the property alone must not advertise a path that doesn't work;</li>
 *   <li>a Cloud model is configured while Cloud isn't available, e.g. {@code spring.ai.model.chat} set without the
 *       profile: Bedrock is enabled only explicitly, so that the banner can't be wrong;</li>
 *   <li>{@code finalysis.ai.ollama.port} isn't 11434 and no {@link OllamaHostGuard} exists: the port is for a test's
 *       stub server, never a way to send a real path's traffic elsewhere.</li>
 * </ul>
 */
@Component
class AiStartupCheck implements SmartInitializingSingleton {

    static final String CLOUD_PROFILE = "aws";
    static final String LOCAL_PROFILE = "local";

    private static final Logger log = LoggerFactory.getLogger(AiStartupCheck.class);

    private final Environment environment;
    private final AiProperties properties;
    private final OllamaProperties ollama;
    private final ObjectProvider<ChatModel> chatModels;
    private final ObjectProvider<EmbeddingModel> embeddingModels;
    private final ObjectProvider<OllamaHostGuard> hostGuards;

    AiStartupCheck(Environment environment, AiProperties properties, OllamaProperties ollama,
                   ObjectProvider<ChatModel> chatModels, ObjectProvider<EmbeddingModel> embeddingModels,
                   ObjectProvider<OllamaHostGuard> hostGuards) {
        this.environment = environment;
        this.properties = properties;
        this.ollama = ollama;
        this.chatModels = chatModels;
        this.embeddingModels = embeddingModels;
        this.hostGuards = hostGuards;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verify(Arrays.asList(environment.getActiveProfiles()), properties.cloudAvailable(),
                chatModels.stream().findAny().isPresent(), embeddingModels.stream().findAny().isPresent());
        verifyPort(ollama.port(), hostGuards.stream().findAny().isPresent());
        log.info("AI: Cloud {}; the path is read from the vault at unlock (a fresh vault: {})",
                properties.cloudAvailable() ? "available (provider " + properties.provider() + ")" : "not available",
                properties.defaultPath());
    }

    static void verify(List<String> activeProfiles, boolean cloudAvailable, boolean hasChatModel,
                       boolean hasEmbeddingModel) {
        if (activeProfiles.contains(CLOUD_PROFILE) && activeProfiles.contains(LOCAL_PROFILE)) {
            throw new IllegalStateException("The '" + CLOUD_PROFILE + "' and '" + LOCAL_PROFILE
                    + "' profiles can't be active together; choose one");
        }
        if (cloudAvailable && (!hasChatModel || !hasEmbeddingModel)) {
            throw new IllegalStateException("finalysis.ai.cloud-available is true but no chat or embedding model is "
                    + "configured for Cloud; enable Cloud through the aws profile");
        }
        if (!cloudAvailable && (hasChatModel || hasEmbeddingModel)) {
            throw new IllegalStateException("A chat or embedding model is configured while Cloud isn't available "
                    + "(finalysis.ai.cloud-available is false); Cloud is enabled only through its profile");
        }
    }

    static void verifyPort(int port, boolean testHostGuard) {
        if (port != OllamaProperties.DEFAULT_PORT && !testHostGuard) {
            throw new IllegalStateException("finalysis.ai.ollama.port is " + port + "; it may differ from "
                    + OllamaProperties.DEFAULT_PORT + " only in a test with a stub server");
        }
    }
}
