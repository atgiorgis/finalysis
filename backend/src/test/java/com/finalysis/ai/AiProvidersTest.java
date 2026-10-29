package com.finalysis.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ai.ollama.OllamaProviderFactory;
import com.finalysis.support.NoCloudAiGuard;
import com.finalysis.support.OllamaStub;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@link AiProviders} on its own (docs/plans/phase-9.md §3.1): what each path builds, the reachability check's reasons,
 * the validation of a switch, and the event a switch publishes. The vault's storage is replaced by an in-memory
 * setting; the HTTP side and the vault lifecycle are {@code AiPathSwitchTest}'s.
 */
class AiProvidersTest {

    private final List<OllamaStub> stubs = new ArrayList<>();
    private final List<Object> events = new ArrayList<>();
    private AiPathSettings.Selection stored;

    @AfterEach
    void stopStubs() {
        stubs.forEach(OllamaStub::close);
    }

    private OllamaStub stub(String... pulled) {
        OllamaStub stub = OllamaStub.start(pulled);
        stubs.add(stub);
        return stub;
    }

    private AiProviders providers(int port, boolean cloudAvailable) {
        AiProperties ai = new AiProperties(false, AiPath.OFF, null, new AiProperties.Roles(null, null, null, null), true);
        if (cloudAvailable) {
            ai = new AiProperties(true, AiPath.OFF, "bedrock", new AiProperties.Roles("main", "cat", "emb", null), true);
        }
        OllamaProperties ollama = new Binder(new MapConfigurationPropertySource(Map.of(
                "finalysis.ai.ollama.port", String.valueOf(port),
                "finalysis.ai.ollama.models.chat", "test-ollama-chat")))
                .bindOrCreate("finalysis.ai.ollama", OllamaProperties.class);
        OllamaProviderFactory factory = new OllamaProviderFactory(new StaticListableBeanFactory(
                Map.of("guard", new NoCloudAiGuard.StubOnlyOllamaHosts())).getBeanProvider(OllamaHostGuard.class));
        AiPathSettings settings = new AiPathSettings(null) {
            @Override
            public Selection read(AiPath defaultPath) {
                return stored == null ? new Selection(defaultPath, null, null) : stored;
            }

            @Override
            public void save(Selection selection) {
                stored = selection;
            }
        };
        CloudProviders cloud = new CloudProviders(ai, new EmbeddingProperties(1024),
                new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.chat.model.ChatModel.class),
                new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.embedding.EmbeddingModel.class),
                new StaticListableBeanFactory().getBeanProvider(PromptCache.class));
        return new AiProviders(ai, ollama, cloud,
                new StaticListableBeanFactory(Map.of("clients", factory)).getBeanProvider(OllamaClients.class),
                settings, events::add);
    }

    @Test
    void aReachableServerGivesItsVersionAndNoReason() {
        OllamaStub stub = stub("test-ollama-chat:latest", "nomic-embed-text:latest");

        AiProviders.State state = providers(stub.port(), false).switchTo(
                new AiPathSettings.Selection(AiPath.LOCAL, null, null), Duration.ofSeconds(5));

        assertThat(state.reachable()).isTrue();
        assertThat(state.reason()).isNull();
        assertThat(state.ollamaVersion()).isEqualTo(OllamaStub.VERSION);
        assertThat(state.providers().provider()).isEqualTo("ollama");
    }

    @Test
    void aServerThatDoesNotAnswerIsOllamaUnreachable() {
        OllamaStub stub = stub();
        stub.stopServing();

        AiProviders.State state = providers(stub.port(), false).switchTo(
                new AiPathSettings.Selection(AiPath.LOCAL, null, null), Duration.ofSeconds(5));

        assertThat(state.reachable()).isFalse();
        assertThat(state.reason()).isEqualTo("Ollama unreachable");
        assertThat(state.path()).as("still the selected path").isEqualTo(AiPath.LOCAL);
        assertThat(stored.path()).isEqualTo(AiPath.LOCAL);
    }

    @Test
    void eitherModelMissingIsNamed() {
        OllamaStub stub = stub("test-ollama-chat:latest");

        AiProviders.State state = providers(stub.port(), false).switchTo(
                new AiPathSettings.Selection(AiPath.LOCAL, null, null), Duration.ofSeconds(5));

        assertThat(state.reason()).isEqualTo("model nomic-embed-text not pulled");
    }

    /** The shipped chat model is a placeholder until the owner sets 9a's pick: it reads as not pulled, never guessed. */
    @Test
    void theUnsetChatModelIsReportedAsNotPulled() {
        OllamaStub stub = stub("ministral-3:14b", "nomic-embed-text:latest");
        AiProviders providers = providers(stub.port(), false);
        OllamaProperties shipped = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("finalysis.ai.ollama", OllamaProperties.class);

        assertThat(shipped.models().chat()).isEqualTo(OllamaProperties.UNSET_CHAT_MODEL);
        assertThat(shipped.port()).isEqualTo(11434);
        assertThat(shipped.models().embedding()).isEqualTo("nomic-embed-text");
        assertThat(shipped.readTimeout(AiPath.SELF_HOSTED)).isEqualTo(Duration.ofSeconds(60));
        assertThat(shipped.readTimeout(AiPath.LOCAL)).isEqualTo(Duration.ofSeconds(300));
        assertThat(AiProviders.pulled(shipped.models().chat(), List.of("ministral-3:14b", "nomic-embed-text:latest")))
                .isFalse();
        assertThat(providers.available()).containsExactly(AiPath.SELF_HOSTED, AiPath.LOCAL);
    }

    @Test
    void cloudIsAvailableOnlyWithTheProfileAndUnbuiltWithoutItsModels() {
        AiProviders withCloud = providers(1, true);

        assertThat(withCloud.available()).containsExactly(AiPath.CLOUD, AiPath.SELF_HOSTED, AiPath.LOCAL);
        AiProviders.State state = withCloud.switchTo(new AiPathSettings.Selection(AiPath.CLOUD, null, null),
                Duration.ofSeconds(1));
        assertThat(state.reachable()).isFalse();
        assertThat(state.reason()).isEqualTo("Cloud isn't available: it needs the aws profile");
        assertThatThrownBy(() -> providers(1, false).switchTo(new AiPathSettings.Selection(AiPath.CLOUD, null, null),
                Duration.ofSeconds(1))).isInstanceOf(AiProviders.InvalidSelectionException.class)
                .hasMessageContaining("isn't available");
    }

    @Test
    void aSwitchPublishesWhetherTheEmbeddingModelChanged() {
        OllamaStub stub = stub("test-ollama-chat:latest", "nomic-embed-text:latest");
        AiProviders providers = providers(stub.port(), false);

        providers.switchTo(new AiPathSettings.Selection(AiPath.LOCAL, null, null), Duration.ofSeconds(5));
        providers.switchTo(new AiPathSettings.Selection(AiPath.SELF_HOSTED, "127.0.0.1", null), Duration.ofSeconds(5));
        providers.switchTo(new AiPathSettings.Selection(AiPath.OFF, null, null), Duration.ofSeconds(5));

        assertThat(events).containsExactly(
                new AiPathChanged(AiPath.LOCAL, "nomic-embed-text", true),
                new AiPathChanged(AiPath.SELF_HOSTED, "nomic-embed-text", false),
                new AiPathChanged(AiPath.OFF, null, false));
    }

    @Test
    void aSwitchIsValidatedBeforeAnythingChanges() {
        AiProviders providers = providers(1, false);
        for (AiPathSettings.Selection refused : List.of(
                new AiPathSettings.Selection(null, null, null),
                new AiPathSettings.Selection(AiPath.SELF_HOSTED, null, null),
                new AiPathSettings.Selection(AiPath.SELF_HOSTED, "http://x:11434", null),
                new AiPathSettings.Selection(AiPath.LOCAL, null, "Not A Region"))) {
            assertThatThrownBy(() -> providers.switchTo(refused, Duration.ofSeconds(1))).as(String.valueOf(refused))
                    .isInstanceOf(AiProviders.InvalidSelectionException.class);
        }
        assertThat(stored).isNull();
        assertThat(events).isEmpty();
    }
}
