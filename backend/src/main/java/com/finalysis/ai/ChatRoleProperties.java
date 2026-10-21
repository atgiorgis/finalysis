package com.finalysis.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The chat role's sampling ({@code finalysis.ai.chat.*}, docs/plans/phase-6.md §2.3). Unset means the provider's
 * default: a 5.5-generation model may refuse sampling options, so chat sends a temperature only when one is set here.
 */
@ConfigurationProperties("finalysis.ai.chat")
public record ChatRoleProperties(Double temperature) {

    public ChatRoleProperties {
        if (temperature != null && (temperature < 0 || temperature > 1)) {
            throw new IllegalArgumentException("finalysis.ai.chat.temperature must be between 0 and 1");
        }
    }
}
