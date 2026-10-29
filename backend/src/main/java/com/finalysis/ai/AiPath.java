package com.finalysis.ai;

/**
 * Where Corgi runs (AI-03; docs/plans/phase-9.md §3.1, §16 "Decided at 9b-1"): the user's choice, stored in the vault
 * ({@code app_setting} {@code ai.path}) and switched without a restart. Which paths are available is fixed at startup
 * ({@link AiProperties#cloudAvailable()}); whether the selected one is reachable is checked at unlock, on a switch and
 * before each AI run ({@link AiProviders}).
 */
public enum AiPath {
    /** No model: rules-only categorization, no AI calls. */
    OFF,
    /** Amazon Bedrock. Data minimization applies and the UI shows a banner. Only with the {@code aws} profile. */
    CLOUD,
    /** Ollama on the user's own server, reached by its Tailscale name. */
    SELF_HOSTED,
    /** Ollama on this computer ({@code 127.0.0.1}). The default for a fresh vault: local AI is the default. */
    LOCAL;

    /** Whether the path's models run on Ollama. */
    public boolean ollama() {
        return this == SELF_HOSTED || this == LOCAL;
    }
}
