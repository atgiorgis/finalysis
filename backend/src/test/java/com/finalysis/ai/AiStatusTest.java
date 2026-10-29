package com.finalysis.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code ai} block of {@code GET /api/status} (docs/plans/phase-9.md §3.3): the path, what can be chosen, whether
 * Corgi can run and why not, the provider and models for the badge, and Self-hosted's server name. Never a region, URL,
 * account or instance id. Bedrock is checked end to end in {@code AwsProfileWiringTest}, the switch in
 * {@code AiPathSwitchTest}.
 */
class AiStatusTest {

    private static final List<AiPath> ALL = List.of(AiPath.CLOUD, AiPath.SELF_HOSTED, AiPath.LOCAL);
    private static final Map<String, String> CLOUD_MODELS = AiStatus.roles("main-model", "categorization-model",
            "embedding-model");
    private static final Map<String, String> OLLAMA_MODELS = AiStatus.roles("ministral-3:14b", "ministral-3:14b",
            "nomic-embed-text");
    private static final Map<AiPath, Map<String, String>> PATH_MODELS = AiStatus.pathModels(ALL, CLOUD_MODELS,
            OLLAMA_MODELS);

    private static ProviderSet providers(String provider) {
        AiProperties ai = new AiProperties(true, AiPath.LOCAL, provider, new AiProperties.Roles("main-model",
                "categorization-model", "embedding-model", null), true);
        return ProviderSet.cloud(ai, 768, mock(ChatModel.class), mock(EmbeddingModel.class), null);
    }

    private static AiProviders.State state(AiPath path, String name, String region, ProviderSet set, boolean reachable,
                                           String reason) {
        return new AiProviders.State(new AiPathSettings.Selection(path, name, region), set, reachable, reason, "0.9.0");
    }

    @Test
    void aReachablePathHasItsProviderAndModels() {
        AiStatus cloud = AiStatus.of(state(AiPath.CLOUD, null, null, providers("bedrock"), true, null), ALL, PATH_MODELS);

        assertThat(cloud).isEqualTo(new AiStatus(AiPath.CLOUD, ALL, true, null, "bedrock", null,
                Map.of("categorization", "categorization-model", "embedding", "embedding-model", "main", "main-model"),
                PATH_MODELS));
    }

    @Test
    void anUnreachablePathSaysWhyAndKeepsItsModels() {
        AiStatus local = AiStatus.of(state(AiPath.LOCAL, null, null, providers("ollama"), false,
                "model finalysis-unset-chat-model not pulled"), ALL, PATH_MODELS);

        assertThat(local.reachable()).isFalse();
        assertThat(local.reason()).isEqualTo("model finalysis-unset-chat-model not pulled");
        assertThat(local.provider()).isEqualTo("ollama");
        assertThat(local.models()).containsEntry("main", "main-model");
    }

    @Test
    void offHasNoProviderAndIsNotReachable() {
        AiStatus off = AiStatus.of(state(AiPath.OFF, null, null, null, false, null), ALL, PATH_MODELS);

        assertThat(off).isEqualTo(new AiStatus(AiPath.OFF, ALL, false, null, null, null, null, PATH_MODELS));
    }

    @Test
    void lockedHasNoPath() {
        AiStatus locked = AiStatus.of(AiProviders.State.LOCKED_STATE, List.of(AiPath.SELF_HOSTED, AiPath.LOCAL),
                AiStatus.pathModels(List.of(AiPath.SELF_HOSTED, AiPath.LOCAL), null, OLLAMA_MODELS));

        assertThat(locked.path()).isNull();
        assertThat(locked.reachable()).isFalse();
        assertThat(locked.reason()).isEqualTo("Vault locked");
        assertThat(locked.available()).containsExactly(AiPath.SELF_HOSTED, AiPath.LOCAL);
    }

    /** Self-hosted shows its Tailscale name and nothing else about the server: no region, URL, port or account. */
    @Test
    void selfHostedShowsOnlyTheServersName() {
        AiStatus selfHosted = AiStatus.of(state(AiPath.SELF_HOSTED, "finalysis-selfhost", "us-east-1",
                providers("ollama"), true, null), ALL, PATH_MODELS);
        String json = JsonMapper.builder().build().writeValueAsString(selfHosted);

        assertThat(selfHosted.server()).isEqualTo("finalysis-selfhost");
        assertThat(json).contains("\"server\":\"finalysis-selfhost\"").contains("\"path\":\"SELF_HOSTED\"")
                .doesNotContain("us-east-1").doesNotContain("region").doesNotContain("http")
                .doesNotContain("11434").doesNotContain("0.9.0");
        assertThat(AiStatus.of(state(AiPath.LOCAL, "finalysis-selfhost", "us-east-1", providers("ollama"), true, null),
                ALL, PATH_MODELS).server()).as("only Self-hosted names a server").isNull();
    }

    @Test
    void theJsonOfOffHasNoNulls() {
        String json = JsonMapper.builder().build().writeValueAsString(
                AiStatus.of(state(AiPath.OFF, null, null, null, false, null), ALL, PATH_MODELS));

        assertThat(json).isEqualTo("{\"path\":\"OFF\",\"available\":[\"CLOUD\",\"SELF_HOSTED\",\"LOCAL\"],"
                + "\"reachable\":false,\"pathModels\":{\"CLOUD\":{\"categorization\":\"categorization-model\","
                + "\"embedding\":\"embedding-model\",\"main\":\"main-model\"},\"SELF_HOSTED\":{\"categorization\":"
                + "\"ministral-3:14b\",\"embedding\":\"nomic-embed-text\",\"main\":\"ministral-3:14b\"},\"LOCAL\":"
                + "{\"categorization\":\"ministral-3:14b\",\"embedding\":\"nomic-embed-text\",\"main\":"
                + "\"ministral-3:14b\"}}}");
    }

    /**
     * Step 9b-2: every available path's configured models, so Settings → Corgi's cards show them all. Configuration
     * only: the same whatever is selected or reachable, and Cloud only when it is available.
     */
    @Test
    void eachAvailablePathListsItsConfiguredModels() {
        AiStatus local = AiStatus.of(state(AiPath.LOCAL, null, null, providers("ollama"), false, "Ollama unreachable"),
                ALL, PATH_MODELS);

        assertThat(local.pathModels()).containsOnlyKeys(AiPath.CLOUD, AiPath.SELF_HOSTED, AiPath.LOCAL);
        assertThat(local.pathModels().get(AiPath.CLOUD)).isEqualTo(CLOUD_MODELS);
        assertThat(local.pathModels().get(AiPath.SELF_HOSTED)).containsEntry("main", "ministral-3:14b")
                .containsEntry("categorization", "ministral-3:14b").containsEntry("embedding", "nomic-embed-text");
        assertThat(local.pathModels().get(AiPath.LOCAL)).isEqualTo(local.pathModels().get(AiPath.SELF_HOSTED));
        assertThat(AiStatus.pathModels(List.of(AiPath.SELF_HOSTED, AiPath.LOCAL), null, OLLAMA_MODELS))
                .as("no Cloud without the aws profile").containsOnlyKeys(AiPath.SELF_HOSTED, AiPath.LOCAL);
        String json = JsonMapper.builder().build().writeValueAsString(AiStatus.of(state(AiPath.SELF_HOSTED,
                "finalysis-selfhost", "us-east-1", providers("ollama"), true, null), ALL, PATH_MODELS));
        assertThat(json).doesNotContain("us-east-1").doesNotContain("http").doesNotContain("11434");
    }
}
