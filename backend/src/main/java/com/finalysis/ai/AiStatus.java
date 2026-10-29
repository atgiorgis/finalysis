package com.finalysis.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The {@code ai} block of {@code GET /api/status} (docs/plans/phase-9.md §3.3): what the UI needs for the badge, the
 * banner and Settings → Corgi. Never a region, account, instance id, URL or credential: Self-hosted's server is its
 * Tailscale name only.
 *
 * @param path      the selected path; null while the vault is locked
 * @param available the paths that can be chosen besides OFF (Cloud only with the {@code aws} profile)
 * @param reachable whether Corgi can run now; false for OFF and while locked
 * @param reason    why not ({@code "Ollama unreachable"}, {@code "model <tag> not pulled"}, ...); null when reachable
 *                  or OFF
 * @param provider  {@code bedrock} or {@code ollama}; null for OFF or a path that can't be built
 * @param server    Self-hosted only: the server's Tailscale name
 * @param models    the model id per role on the path; null with {@code provider}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiStatus(AiPath path, List<AiPath> available, boolean reachable, String reason, String provider,
                       String server, Map<String, String> models) {

    public static AiStatus of(AiProviders providers) {
        return of(providers.state(), providers.available());
    }

    static AiStatus of(AiProviders.State state, List<AiPath> available) {
        ProviderSet set = state.providers();
        Map<String, String> models = null;
        if (set != null) {
            models = new TreeMap<>();
            models.put("categorization", set.categorization().modelId());
            models.put("embedding", set.embedding().modelId());
            models.put("main", set.chat().modelId());
        }
        AiPath path = state.path();
        String server = path == AiPath.SELF_HOSTED ? state.selection().selfhostName() : null;
        return new AiStatus(path, available, state.usable(), state.reason(), set == null ? null : set.provider(),
                server, models);
    }
}
