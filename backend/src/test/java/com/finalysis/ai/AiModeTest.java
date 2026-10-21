package com.finalysis.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** AI-03: AI is off unless a profile turns it on, and an inconsistent setup fails startup (docs/plans/phase-4.md §7). */
class AiModeTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiConfig.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    static class AiConfig {

        @Bean
        AiStartupCheck aiStartupCheck(org.springframework.core.env.Environment environment, AiProperties properties,
                                      org.springframework.beans.factory.ObjectProvider<ChatModel> chat,
                                      org.springframework.beans.factory.ObjectProvider<EmbeddingModel> embedding) {
            return new AiStartupCheck(environment, properties, chat, embedding);
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

    private static final String[] CLOUD = {"finalysis.ai.mode=CLOUD", "finalysis.ai.provider=bedrock",
            "finalysis.ai.roles.categorization=chat-model", "finalysis.ai.roles.embedding=embedding-model",
            "finalysis.ai.roles.main=main-model"};

    @Test
    void offByDefault() {
        AiProperties properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("finalysis.ai", AiProperties.class);

        assertThat(properties.mode()).isEqualTo(AiMode.OFF);
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.nativeStructuredOutput()).isTrue();
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(ChatModel.class)
                .getBean(AiProperties.class).extracting(AiProperties::mode).isEqualTo(AiMode.OFF));
    }

    @Test
    void cloudWithBothModelsStarts() {
        runner.withUserConfiguration(Models.class).withPropertyValues(CLOUD)
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(AiProperties.class).extracting(AiProperties::mode).isEqualTo(AiMode.CLOUD));
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

    @Test
    void aModeWithoutModelsFailsStartup() {
        runner.withPropertyValues(CLOUD)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("finalysis.ai.mode is CLOUD but no chat or embedding model"));
        assertThatThrownBy(() -> AiStartupCheck.verify(List.of(), AiMode.LOCAL, true, false))
                .hasMessageContaining("no chat or embedding model");
    }

    @Test
    void aModelWithoutAModeFailsStartup() {
        runner.withUserConfiguration(Models.class)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("configured while finalysis.ai.mode is OFF"));
        assertThatThrownBy(() -> AiStartupCheck.verify(List.of(), AiMode.OFF, false, true))
                .hasMessageContaining("mode is OFF");
    }

    @Test
    void anEnabledModeNeedsAProviderAndRoleModels() {
        runner.withUserConfiguration(Models.class).withPropertyValues("finalysis.ai.mode=CLOUD")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage("finalysis.ai.provider must be set when finalysis.ai.mode is CLOUD"));
        assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource(Map.of(
                "finalysis.ai.mode", "CLOUD", "finalysis.ai.provider", "bedrock",
                "finalysis.ai.roles.main", "main-model", "finalysis.ai.roles.categorization", "chat-model")))
                .bind("finalysis.ai", AiProperties.class))
                .isInstanceOf(BindException.class)
                .hasRootCauseMessage("finalysis.ai.roles.embedding must be set when finalysis.ai.mode is CLOUD");
        // Phase 6: chat's main role is required too
        assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource(Map.of(
                "finalysis.ai.mode", "CLOUD", "finalysis.ai.provider", "bedrock",
                "finalysis.ai.roles.categorization", "chat-model", "finalysis.ai.roles.embedding", "embedding-model")))
                .bind("finalysis.ai", AiProperties.class))
                .isInstanceOf(BindException.class)
                .hasRootCauseMessage("finalysis.ai.roles.main must be set when finalysis.ai.mode is CLOUD");
    }

    @Test
    void statusIsModeOnlyWhenOff() {
        AiProperties off = new AiProperties(AiMode.OFF, null, new AiProperties.Roles(null, null, null, null), true);
        AiProperties cloud = new AiProperties(AiMode.CLOUD, "bedrock",
                new AiProperties.Roles("main-model", "chat-model", "embedding-model", ""), true);

        assertThat(AiStatus.of(off)).isEqualTo(new AiStatus(AiMode.OFF, null, null));
        assertThat(AiStatus.of(cloud)).isEqualTo(new AiStatus(AiMode.CLOUD, "bedrock",
                Map.of("categorization", "chat-model", "embedding", "embedding-model", "main", "main-model")));
    }
}
