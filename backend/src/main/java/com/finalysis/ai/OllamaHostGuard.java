package com.finalysis.ai;

import java.net.URI;

/**
 * Checks every Ollama request before it is sent. Only the test suite registers one ({@code NoCloudAiGuard}): it fails
 * any request whose host isn't a test's stub server on loopback. Production has none. Its presence is also what lets
 * {@code finalysis.ai.ollama.port} differ from 11434 ({@code AiStartupCheck}).
 */
public interface OllamaHostGuard {

    /** @throws Error (an {@code AssertionError} in the tests) to stop the request before it is sent */
    void check(URI request);
}
