package com.finalysis.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * AI-03 (docs/plans/phase-9.md §3.1): which paths exist is fixed at startup, Cloud only explicitly, and an inconsistent
 * setup fails startup: Cloud advertised without its models, a model without Cloud, both profiles, a stub port outside
 * a test.
 */
class AiPathTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiConfig.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AiProperties.class, OllamaProperties.class})
    static class AiConfig {

        @Bean
        AiStartupCheck aiStartupCheck(Environment environment, AiProperties properties, OllamaProperties ollama,
                                      ObjectProvider<ChatModel> chat, ObjectProvider<EmbeddingModel> embedding,
                                      ObjectProvider<OllamaHostGuard> guards) {
            return new AiStartupCheck(environment, properties, ollama, chat, embedding, guards);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Models {

        @Bean
        ChatModel chatModel() {
            return mock(ChatModel.class);
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return mock(EmbeddingModel.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestHostGuard {

        @Bean
        OllamaHostGuard ollamaHostGuard() {
            return (URI request) -> {
            };
        }
    }

    private static final String[] CLOUD = {"finalysis.ai.cloud-available=true", "finalysis.ai.provider=bedrock",
            "finalysis.ai.roles.categorization=chat-model", "finalysis.ai.roles.embedding=embedding-model",
            "finalysis.ai.roles.main=main-model"};

    @Test
    void cloudIsNotAvailableAndLocalIsTheDefaultPath() {
        AiProperties properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("finalysis.ai", AiProperties.class);

        assertThat(properties.cloudAvailable()).isFalse();
        assertThat(properties.defaultPath()).isEqualTo(AiPath.LOCAL);
        assertThat(properties.nativeStructuredOutput()).isTrue();
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ChatModel.class));
    }

    @Test
    void cloudWithBothModelsStarts() {
        runner.withUserConfiguration(Models.class).withPropertyValues(CLOUD)
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(AiProperties.class).extracting(AiProperties::cloudAvailable).isEqualTo(true));
    }

    @Test
    void bothProfilesTogetherFailStartup() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("aws", "local"))
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("'aws' and 'local' profiles can't be active together"));
    }

    @Test
    void eitherProfileAloneIsFine() {
        assertThat(List.of("aws", "local")).allSatisfy(profile ->
                runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev", profile))
                        .run(context -> assertThat(context).hasNotFailed()));
    }

    /** The property alone can't advertise a path that doesn't work. */
    @Test
    void cloudAvailableWithoutModelsFailsStartup() {
        runner.withPropertyValues(CLOUD)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("finalysis.ai.cloud-available is true but no chat or embedding model"));
        assertThatThrownBy(() -> AiStartupCheck.verify(List.of(), true, true, false))
                .hasMessageContaining("no chat or embedding model");
        assertThatThrownBy(() -> AiStartupCheck.verify(List.of(), true, false, true))
                .hasMessageContaining("no chat or embedding model");
    }

    @Test
    void aModelWithoutCloudFailsStartup() {
        runner.withUserConfiguration(Models.class)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("configured while Cloud isn't available"));
        assertThatThrownBy(() -> AiStartupCheck.verify(List.of(), false, false, true))
                .hasMessageContaining("Cloud isn't available");
    }

    @Test
    void cloudNeedsAProviderAndRoleModels() {
        runner.withUserConfiguration(Models.class).withPropertyValues("finalysis.ai.cloud-available=true")
                .run(context -> assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "finalysis.ai.provider must be set when finalysis.ai.cloud-available is true"));
        assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource(Map.of(
                "finalysis.ai.cloud-available", "true", "finalysis.ai.provider", "bedrock",
                "finalysis.ai.roles.main", "main-model", "finalysis.ai.roles.categorization", "chat-model")))
                .bind("finalysis.ai", AiProperties.class))
                .isInstanceOf(BindException.class)
                .hasRootCauseMessage("finalysis.ai.roles.embedding must be set when finalysis.ai.cloud-available is true");
        assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource(Map.of(
                "finalysis.ai.cloud-available", "true", "finalysis.ai.provider", "bedrock",
                "finalysis.ai.roles.categorization", "chat-model", "finalysis.ai.roles.embedding", "embedding-model")))
                .bind("finalysis.ai", AiProperties.class))
                .isInstanceOf(BindException.class)
                .hasRootCauseMessage("finalysis.ai.roles.main must be set when finalysis.ai.cloud-available is true");
    }

    @Test
    void theDefaultPathIsNeverSelfHostedNorAnUnavailableCloud() {
        assertThatThrownBy(() -> bind(Map.of("finalysis.ai.default-path", "SELF_HOSTED")))
                .hasRootCauseMessage("finalysis.ai.default-path can't be SELF_HOSTED: it needs a server name, chosen "
                        + "in Settings");
        assertThatThrownBy(() -> bind(Map.of("finalysis.ai.default-path", "CLOUD")))
                .rootCause().hasMessageContaining("Cloud isn't available");
        assertThat(bind(Map.of("finalysis.ai.default-path", "OFF")).defaultPath()).isEqualTo(AiPath.OFF);
    }

    /** The port is for a test's stub: another one without a test's host guard fails startup. */
    @Test
    void anotherOllamaPortNeedsATestHostGuard() {
        runner.withPropertyValues("finalysis.ai.ollama.port=11500")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("finalysis.ai.ollama.port is 11500; it may differ from 11434 only in a "
                                + "test with a stub server"));
        runner.withUserConfiguration(TestHostGuard.class).withPropertyValues("finalysis.ai.ollama.port=11500")
                .run(context -> assertThat(context).hasNotFailed());
        AiStartupCheck.verifyPort(11434, false);
        AiStartupCheck.verifyPort(11500, true);
        assertThatThrownBy(() -> AiStartupCheck.verifyPort(11500, false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlySelfHostedAndLocalRunOnOllama() {
        assertThat(List.of(AiPath.values())).filteredOn(AiPath::ollama)
                .containsExactly(AiPath.SELF_HOSTED, AiPath.LOCAL);
    }

    private static AiProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("finalysis.ai", AiProperties.class);
    }
}
