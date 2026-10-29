package com.finalysis.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
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
 * @param pathModels each available path's configured model id per role, for Settings → Corgi's cards: Cloud's roles,
 *                   and on Self-hosted and Local Ollama's chat model (chat and categorization) and embedding model.
 *                   Configuration only: nothing is checked for a path that isn't selected (Phase 9 step 9b-2)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiStatus(AiPath path, List<AiPath> available, boolean reachable, String reason, String provider,
                       String server, Map<String, String> models, Map<AiPath, Map<String, String>> pathModels) {

    public static AiStatus of(AiProviders providers) {
        return of(providers.state(), providers.available(), providers.configuredModels());
    }

    /** The configured models per role: chat ({@code main}), categorization and embedding. */
    static Map<String, String> roles(String main, String categorization, String embedding) {
        Map<String, String> roles = new TreeMap<>();
        roles.put("categorization", categorization);
        roles.put("embedding", embedding);
        roles.put("main", main);
        return roles;
    }

    /** Each path of {@code available}, in that order, with its configured models. */
    static Map<AiPath, Map<String, String>> pathModels(List<AiPath> available, Map<String, String> cloud,
                                                       Map<String, String> ollama) {
        Map<AiPath, Map<String, String>> models = new LinkedHashMap<>();
        for (AiPath path : available) {
            models.put(path, path == AiPath.CLOUD ? cloud : ollama);
        }
        return models;
    }

    static AiStatus of(AiProviders.State state, List<AiPath> available,
                       Map<AiPath, Map<String, String>> pathModels) {
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
                server, models, pathModels);
    }
}
