package com.finalysis.ai;

/**
 * Where models run (AI-03). Set by {@code finalysis.ai.mode}, which only the profiles change: none gives
 * {@link #OFF}, {@code aws} gives {@link #CLOUD}, and the later {@code local} profile {@link #LOCAL}.
 */
public enum AiMode {
    /** No model: rules-only categorization, no AI calls. The default. */
    OFF,
    /** A cloud provider (Bedrock). Data minimization applies and the UI shows a banner. */
    CLOUD,
    /** A model on this machine (Ollama, Phase 10). */
    LOCAL
}
