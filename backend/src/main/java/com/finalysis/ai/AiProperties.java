package com.finalysis.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI settings fixed at startup ({@code finalysis.ai.*}, docs/plans/phase-4.md §7, docs/plans/phase-9.md §3.1). The
 * path itself is the user's, in the vault ({@link AiPathSettings}); these say which paths exist and what Cloud runs.
 *
 * @param cloudAvailable         whether the Cloud path can be chosen: true only from {@code application-aws.yml} (or a
 *                               test with a fake model). Startup refuses it without a chat and an embedding model
 *                               ({@code AiStartupCheck}), so the property alone can't advertise a path that doesn't
 *                               work
 * @param defaultPath            the path of a vault with no {@code ai.path} setting: {@link AiPath#LOCAL} ("local AI
 *                               is the default"). Never set in the shipped files; the test suite's guard sets OFF
 * @param provider               Cloud's provider name for the status block (e.g. {@code bedrock})
 * @param roles                  Cloud's model id per role (AI-01); calls pass it in their options
 * @param nativeStructuredOutput use the provider's native JSON-schema output; false falls back to Spring AI's
 *                               format instructions
 */
@ConfigurationProperties("finalysis.ai")
public record AiProperties(@DefaultValue("false") boolean cloudAvailable, @DefaultValue("LOCAL") AiPath defaultPath,
                           String provider, @DefaultValue Roles roles,
                           @DefaultValue("true") boolean nativeStructuredOutput) {

    public AiProperties {
        if (cloudAvailable) {
            require(provider, "provider");
            require(roles.main(), "roles.main");
            require(roles.categorization(), "roles.categorization");
            require(roles.embedding(), "roles.embedding");
        }
        if (defaultPath == AiPath.SELF_HOSTED) {
            throw new IllegalArgumentException("finalysis.ai.default-path can't be SELF_HOSTED: it needs a server name, "
                    + "chosen in Settings");
        }
        if (defaultPath == AiPath.CLOUD && !cloudAvailable) {
            throw new IllegalArgumentException("finalysis.ai.default-path is CLOUD but Cloud isn't available; it needs "
                    + "finalysis.ai.cloud-available (the aws profile)");
        }
    }

    /**
     * Cloud's model id per role. {@code main} is chat's (Phase 6); {@code vision} is used from Phase 8.
     */
    public record Roles(String main, String categorization, String embedding, String vision) {
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("finalysis.ai." + name + " must be set when finalysis.ai.cloud-available "
                    + "is true");
        }
    }
}
