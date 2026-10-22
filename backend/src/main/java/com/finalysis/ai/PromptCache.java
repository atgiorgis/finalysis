package com.finalysis.ai;

import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * Prompt caching of chat's fixed prefix (docs/plans/phase-6.md, step 8c), as a provider places it. Chat asks for one
 * cache point after the tool definitions and one after the system prompt and the context block: what every round of
 * every conversation on the same data and day starts with. Whatever follows (the conversation's summary, its earlier
 * turns, the question, this turn's tool calls and results) is never inside a cached prefix. A cache entry matches only
 * a byte-identical prefix, so no conversation's content can reach another through it.
 *
 * <p>Only the provider's configuration implements it ({@code ai.bedrock}); without one, chat runs uncached.
 */
public interface PromptCache {

    /**
     * The options with the cache points.
     *
     * @param systemMessages       the prompt's system messages, which come first
     * @param cachedSystemMessages how many of them the cached prefix ends after
     * @throws IllegalArgumentException if the provider can't place a cache point there
     */
    ChatOptions withCachePoints(ChatOptions options, int systemMessages, int cachedSystemMessages);

    /** Whether a failed call is the provider refusing cache points for the model (then chat sends it uncached). */
    boolean refusedCaching(Throwable error);
}
