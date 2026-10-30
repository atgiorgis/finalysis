package com.finalysis.ai;

import java.net.URI;
import java.util.List;

/**
 * Builds Ollama's clients for a path's base URL (docs/plans/phase-9.md §3.2). Implemented in {@code ai.ollama}, the
 * only package that names Spring AI's Ollama classes; nothing here depends on it.
 */
public interface OllamaClients {

    /**
     * New clients for this base URL, with this read timeout per call and this context window ({@code num_ctx}) on every
     * chat and categorization request. Nothing is sent.
     */
    ProviderSet create(URI baseUrl, OllamaProperties.Models models, int embeddingDimensions, int numCtx,
                       java.time.Duration readTimeout);

    /**
     * Ollama's version ({@code GET /api/version}) and the model tags it has pulled ({@code GET /api/tags}), with the
     * probe's short timeout.
     *
     * @throws RuntimeException when the server can't be reached or answers with an error
     */
    Probe probe(URI baseUrl, java.time.Duration timeout);

    /**
     * Ollama's version alone ({@code GET /api/version}), for Settings' Test button on the Self-hosted server
     * (docs/plans/phase-9.md §5.5). A status check: the server's idle watchdog doesn't count it.
     *
     * @throws RuntimeException when the server can't be reached or answers with an error; its cause says why
     */
    String version(URI baseUrl, java.time.Duration timeout);

    /**
     * Loads a model into memory without generating anything: {@code POST /api/generate} with the model's name, no
     * prompt, and the context window chat will ask for ({@code options.num_ctx}), since Ollama reloads a model whose
     * loaded context differs from a request's. Nothing but the tag and that number is sent (§5.5's warm-up after
     * Start). The server's watchdog counts it as a model request.
     *
     * @throws RuntimeException when the model doesn't load in time or the server answers with an error
     */
    void warmUp(URI baseUrl, String model, int numCtx, java.time.Duration timeout);

    /**
     * @param version Ollama's version as it reports it
     * @param models  the pulled models' names, e.g. {@code nomic-embed-text:latest}
     */
    record Probe(String version, List<String> models) {

        public Probe {
            models = List.copyOf(models);
        }
    }
}
