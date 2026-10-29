package com.finalysis.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The stored path (docs/plans/phase-9.md §3.1): what a Tailscale name and an AWS region may be, and how stored values
 * are read. A name is a host name and never a URL; a stored value that doesn't validate is ignored, never trusted.
 */
class AiPathSettingsTest {

    @ParameterizedTest
    @ValueSource(strings = {"finalysis-selfhost", "finalysis-selfhost.tail1234.ts.net", "gpu1", "127.0.0.1", "A-b-9"})
    void serverNames(String name) {
        assertThat(AiPathSettings.validServerName(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "http://finalysis-selfhost", "finalysis-selfhost:11434", "a..b", ".a", "a.",
            "-a", "a-", "user@host", "host/path", "host?x=1", "two words", "host#frag", "ünïcode"})
    void notServerNames(String name) {
        assertThat(AiPathSettings.validServerName(name)).isFalse();
    }

    @Test
    void aNameIsAtMost253Characters() {
        String label = "a".repeat(63);
        String longest = String.join(".", label, label, label, "a".repeat(61));

        assertThat(longest).hasSize(253);
        assertThat(AiPathSettings.validServerName(longest)).isTrue();
        assertThat(AiPathSettings.validServerName(longest + "a")).isFalse();
        assertThat(AiPathSettings.validServerName("a".repeat(64))).as("a label of 64").isFalse();
        assertThat(AiPathSettings.validServerName(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "eu-west-2", "ap-southeast-2", "us-gov-west-1"})
    void regions(String region) {
        assertThat(AiPathSettings.validRegion(region)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "US-EAST-1", "us_east_1", "us east 1", "useast1", "us-east-1;", "arn:aws:x"})
    void notRegions(String region) {
        assertThat(AiPathSettings.validRegion(region)).isFalse();
    }

    @Test
    void aFreshVaultGetsTheDefaultPath() {
        assertThat(AiPathSettings.resolve(Map.of(), AiPath.LOCAL))
                .isEqualTo(new AiPathSettings.Selection(AiPath.LOCAL, null, null));
    }

    @Test
    void storedValuesAreReadAndBadOnesIgnored() {
        assertThat(AiPathSettings.resolve(Map.of("ai.path", "SELF_HOSTED", "ai.selfhost.name", "gpu1",
                "ai.selfhost.region", "us-east-1"), AiPath.LOCAL))
                .isEqualTo(new AiPathSettings.Selection(AiPath.SELF_HOSTED, "gpu1", "us-east-1"));
        assertThat(AiPathSettings.resolve(Map.of("ai.path", "MARS", "ai.selfhost.name", "http://x",
                "ai.selfhost.region", "nowhere"), AiPath.LOCAL))
                .isEqualTo(new AiPathSettings.Selection(AiPath.LOCAL, null, null));
    }
}
