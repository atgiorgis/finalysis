package com.finalysis.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The {@code ai} block of {@code GET /api/status} carries the provider from config for the chat header's path badge
 * (Phase 7 step 7e): the UI never hardcodes it. Bedrock is checked end to end in {@code AwsProfileWiringTest}.
 */
class AiStatusTest {

    private static AiProperties properties(AiMode mode, String provider) {
        return new AiProperties(mode, provider, new AiProperties.Roles("chat-model", "categorization-model",
                "embedding-model", null), true);
    }

    @Test
    void theProviderIsTheConfiguredOneInEachMode() {
        assertThat(AiStatus.of(properties(AiMode.CLOUD, "bedrock")))
                .extracting(AiStatus::mode, AiStatus::provider).containsExactly(AiMode.CLOUD, "bedrock");
        assertThat(AiStatus.of(properties(AiMode.LOCAL, "ollama")))
                .extracting(AiStatus::mode, AiStatus::provider).containsExactly(AiMode.LOCAL, "ollama");
    }

    @Test
    void offHasNoProvider() {
        AiStatus off = AiStatus.of(new AiProperties(AiMode.OFF, "bedrock", new AiProperties.Roles(null, null, null, null),
                true));
        assertThat(off.provider()).isNull();
        assertThat(off.models()).isNull();
    }
}
