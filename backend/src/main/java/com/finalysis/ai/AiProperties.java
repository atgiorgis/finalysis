package com.finalysis.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI settings ({@code finalysis.ai.*}, docs/plans/phase-4.md §7). Without a profile everything is off.
 *
 * @param mode                   {@link AiMode#OFF} unless a profile sets it
 * @param provider               the provider's short name for the status block (e.g. {@code bedrock}); required
 *                               when a model is on
 * @param roles                  the model id per role (AI-01); calls pass it in their options, so per-role models
 *                               need no provider code
 * @param nativeStructuredOutput use the provider's native JSON-schema output; false falls back to Spring AI's
 *                               format instructions
 */
@ConfigurationProperties("finalysis.ai")
public record AiProperties(@DefaultValue("OFF") AiMode mode, String provider, @DefaultValue Roles roles,
                           @DefaultValue("true") boolean nativeStructuredOutput) {

    public AiProperties {
        if (mode != AiMode.OFF) {
            require(provider, "provider", mode);
            require(roles.categorization(), "roles.categorization", mode);
            require(roles.embedding(), "roles.embedding", mode);
        }
    }

    public boolean enabled() {
        return mode != AiMode.OFF;
    }

    /**
     * A model id per role. {@code main} (chat) and {@code vision} are used from Phases 6 and 8.
     */
    public record Roles(String main, String categorization, String embedding, String vision) {
    }

    private static void require(String value, String name, AiMode mode) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("finalysis.ai." + name + " must be set when finalysis.ai.mode is " + mode);
        }
    }
}
