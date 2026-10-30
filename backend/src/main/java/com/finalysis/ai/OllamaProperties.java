package com.finalysis.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The Ollama paths' settings ({@code finalysis.ai.ollama.*}, docs/plans/phase-9.md §3.2). The hosts are fixed: Local
 * is {@code 127.0.0.1}, Self-hosted the server's Tailscale name ({@code ai.selfhost.name}).
 *
 * @param port                  Ollama's port on both paths, 11434. <strong>For tests only:</strong> a test's stub
 *                              server listens on a random loopback port. Startup refuses another port unless a test's
 *                              host guard is present ({@code AiStartupCheck}), so it can't redirect a real path
 * @param models                the model tags, the same on both paths
 * @param embeddingDimensions   the embedding model's vector length (nomic-embed-text: 768), checked on every vector
 * @param selfHostedReadTimeout one call's read timeout on Self-hosted (a GPU instance over Tailscale)
 * @param localReadTimeout      one call's read timeout on Local (a CPU laptop may take minutes)
 * @param probeTimeout          the reachability check's connect and read timeout ({@code /api/version}, {@code /api/tags})
 * @param numCtx                the context window asked for on every chat and categorization request
 *                              ({@code options.num_ctx}, AI-05): Ollama's own default (4096 on recent versions) would
 *                              cut Corgi's 11–15K-token prompts, tool definitions included. Chat's input budget and
 *                              output cap must fit in it ({@code ChatStartupCheck})
 */
@ConfigurationProperties("finalysis.ai.ollama")
public record OllamaProperties(@DefaultValue("11434") int port, @DefaultValue Models models,
                               @DefaultValue("768") int embeddingDimensions,
                               @DefaultValue("60s") Duration selfHostedReadTimeout,
                               @DefaultValue("300s") Duration localReadTimeout,
                               @DefaultValue("3s") Duration probeTimeout,
                               @DefaultValue("16384") int numCtx) {

    public static final int DEFAULT_PORT = 11434;

    /**
     * No usable chat model is assumed: the owner sets the tag of 9a's pick (Ministral 3 14B) as Ollama names it. Until
     * then the reachability check reports this placeholder as not pulled, and Corgi stays off on the Ollama paths.
     */
    public static final String UNSET_CHAT_MODEL = "finalysis-unset-chat-model";

    public OllamaProperties {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("finalysis.ai.ollama.port must be 1 to 65535");
        }
        if (embeddingDimensions < 1) {
            throw new IllegalArgumentException("finalysis.ai.ollama.embedding-dimensions must be at least 1");
        }
        if (numCtx < 1) {
            throw new IllegalArgumentException("finalysis.ai.ollama.num-ctx must be at least 1");
        }
        for (Duration timeout : new Duration[] {selfHostedReadTimeout, localReadTimeout, probeTimeout}) {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("finalysis.ai.ollama timeouts must be positive");
            }
        }
    }

    /** One call's read timeout on an Ollama path. */
    public Duration readTimeout(AiPath path) {
        return switch (path) {
            case LOCAL -> localReadTimeout;
            case SELF_HOSTED -> selfHostedReadTimeout;
            default -> throw new IllegalArgumentException(path + " isn't an Ollama path");
        };
    }

    /**
     * @param chat      chat's and categorization's model (one model on these paths)
     * @param embedding the embedding model
     */
    public record Models(@DefaultValue(UNSET_CHAT_MODEL) String chat, @DefaultValue("nomic-embed-text") String embedding) {

        public Models {
            if (chat == null || chat.isBlank() || embedding == null || embedding.isBlank()) {
                throw new IllegalArgumentException("finalysis.ai.ollama.models.chat and .embedding must be set");
            }
        }
    }
}
