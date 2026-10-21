package com.finalysis.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.TreeMap;

/**
 * The {@code ai} block of {@code GET /api/status}: what the UI needs for the cloud banner (docs/plans/phase-4.md
 * §7). Never a region, account or credential. {@code {"mode": "OFF"}} when AI is off.
 *
 * @param provider the provider's short name, e.g. {@code bedrock}; null when off
 * @param models   the model id per role in use; null when off
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiStatus(AiMode mode, String provider, Map<String, String> models) {

    public static AiStatus of(AiProperties properties) {
        if (!properties.enabled()) {
            return new AiStatus(AiMode.OFF, null, null);
        }
        Map<String, String> models = new TreeMap<>();
        models.put("categorization", properties.roles().categorization());
        models.put("embedding", properties.roles().embedding());
        models.put("main", properties.roles().main());
        return new AiStatus(properties.mode(), properties.provider(), models);
    }
}
