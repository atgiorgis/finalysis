/**
 * Spring AI's Ollama clients for the Self-hosted and Local paths (docs/plans/phase-9.md §3.2): the only package that may
 * name {@code org.springframework.ai.ollama} classes, and nothing outside {@code com.finalysis.ai} depends on it
 * ({@code PackageDependencyTest}). {@code com.finalysis.ai} reaches it through {@link com.finalysis.ai.OllamaClients}.
 */
package com.finalysis.ai.ollama;
