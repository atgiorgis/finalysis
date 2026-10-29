package com.finalysis.chat;

import com.finalysis.ai.ActiveProviders;
import com.finalysis.ai.AiCallLog;
import com.finalysis.ai.AiPath;
import com.finalysis.ai.AiPrices;
import com.finalysis.ai.ChatProvider;
import com.finalysis.ai.ChatRoleOptions;
import com.finalysis.ai.ChatRoleProperties;
import com.finalysis.ai.PromptCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * One chat round (docs/plans/phase-6.md §10.1): {@code ChatModel.call(Prompt)} with the turn's messages, the
 * {@code main} role's options and the tool definitions, and nothing else: no {@code ChatClient}, so no advisor that
 * could run a tool, log content or add memory. Tool execution is the loop's. A tool call comes back here unexecuted
 * (Spring AI 2.0.1's model only resolves definitions; {@code BedrockToolCallsTest} proves it on the real Converse
 * model), and the callbacks refuse to run anyway ({@link ChatToolCallbacks}).
 *
 * <p>A provider may split one assistant message into several generations (Bedrock Converse puts the text in one and
 * the tool calls in another), so a reply gathers them: every tool call in order, and the texts joined. The message
 * appended to the next round is the one that carries the tool calls, as returned (provider-specific content such as
 * reasoning stays on it), with the joined text.
 *
 * <p>Every call writes one {@link AiCallLog} line with role {@value #ROLE}: tokens, the tool results in the prompt as
 * {@code items}, the outcome ({@code LIMIT} when the answer stopped at the output cap), never content.
 *
 * <p><b>Prompt caching</b> (step 8c). A chat round asks the provider to cache its fixed prefix: the tool definitions,
 * the system prompt and the context block ({@link PromptCache}); never the summary, the history, the question or the
 * turn's tool results. Only when {@code finalysis.ai.chat.prompt-cache} is on, the model has cache prices, and the
 * provider can place cache points ({@link #promptCaching}). If the provider refuses the cache points for a model, the
 * call is sent once more without them, and that model runs uncached until the app restarts. The summaries'
 * calls ({@link #complete}) are never cached: their prompts differ every time.
 *
 * <p><b>Paths</b> (docs/plans/phase-9.md §3.2, §4.2). The model, its id and the prompt cache are the active path's
 * ({@link ActiveProviders}). Prices, and with them every cost estimate and cost limit, apply on Cloud only
 * ({@link #price()}); the Ollama paths have no cache.
 */
@Component
public class ChatModelClient {

    static final String ROLE = "chat";
    public static final String SUMMARY_ROLE = "chat-summary";
    public static final String MONTHLY_ROLE = "summary";

    /** The cached prefix ends after the system prompt and the context block ({@link ChatPrompt#start}). */
    static final int CACHED_SYSTEM_MESSAGES = ChatPrompt.HISTORY_START;

    private static final Logger log = LoggerFactory.getLogger(ChatModelClient.class);

    private final ActiveProviders providers;
    private final ChatRoleOptions options;
    private final ChatToolCallbacks tools;
    private final ChatRoleProperties role;
    private final AiPrices prices;
    /** Models whose provider refused cache points since the app started. */
    private final Set<String> refusedCaching = ConcurrentHashMap.newKeySet();

    public ChatModelClient(ActiveProviders providers, ChatRoleOptions options, ChatToolCallbacks tools,
                           ChatRoleProperties role, AiPrices prices) {
        this.providers = providers;
        this.options = options;
        this.tools = tools;
        this.role = role;
        this.prices = prices;
    }

    /** Whether chat's rounds cache their fixed prefix, and why not (step 8c). */
    public enum PromptCaching {
        ON,
        /** {@code finalysis.ai.chat.prompt-cache} is false. */
        OFF_SETTING,
        /** The main model has no cache prices, so chat doesn't ask it to cache. */
        OFF_NO_CACHE_PRICES,
        /** The provider has no cache points (no {@link PromptCache}). */
        OFF_PROVIDER,
        /** The provider refused cache points for this model; its calls go uncached. */
        OFF_REFUSED
    }

    public PromptCaching promptCaching() {
        if (!role.promptCache()) {
            return PromptCaching.OFF_SETTING;
        }
        if (price().filter(AiPrices.Price::cachePriced).isEmpty()) {
            return PromptCaching.OFF_NO_CACHE_PRICES;
        }
        if (cache().isEmpty()) {
            return PromptCaching.OFF_PROVIDER;
        }
        return refusedCaching.contains(modelId()) ? PromptCaching.OFF_REFUSED : PromptCaching.ON;
    }

    /**
     * What one round returned.
     *
     * @param message      the assistant message to append to the next round, unchanged but for the joined text
     * @param toolCalls    the tool calls, in order, with the model's ids; empty for an answer
     * @param text         the answer's text; may be blank
     * @param cut              the model stopped at the output-token cap
     * @param inputTokens      input tokens outside a cached prefix; null when the provider didn't report usage
     * @param outputTokens     null when the provider didn't report usage
     * @param cacheReadTokens  prefix tokens read from the cache; 0 when none or not reported
     * @param cacheWriteTokens prefix tokens written to the cache; 0 when none or not reported
     * @param cached           the call asked for caching
     */
    public record Reply(AssistantMessage message, List<AssistantMessage.ToolCall> toolCalls, String text, boolean cut,
                        Integer inputTokens, Integer outputTokens, int cacheReadTokens, int cacheWriteTokens,
                        boolean cached, long millis) {

        public Reply {
            toolCalls = List.copyOf(toolCalls);
        }

        public boolean usageReported() {
            return inputTokens != null;
        }
    }

    public boolean enabled() {
        return providers.usable() && providers.chat() != null;
    }

    public String modelId() {
        return options.modelId();
    }

    /** The active path: chat's costs and limits depend on it (§4.2). Null while the vault is locked. */
    public AiPath path() {
        return providers.path();
    }

    /** The main model's price: Cloud only. Off Cloud there is no token cost, so no estimate and no cost limit. */
    public Optional<AiPrices.Price> price() {
        return providers.path() == AiPath.CLOUD ? prices.of(modelId()) : Optional.empty();
    }

    private Optional<PromptCache> cache() {
        ChatProvider chat = providers.chat();
        return chat == null ? Optional.empty() : chat.promptCache();
    }

    /**
     * @param toolResults the tool results in these messages, for the log's {@code items}
     * @throws IllegalStateException if AI is off
     * @throws RuntimeException      whatever the model client throws; the call is logged with the class name first
     */
    public Reply call(List<Message> messages, int maxOutputTokens, int toolResults) {
        return call(messages, maxOutputTokens, toolResults, List.of());
    }

    /** As {@link #call(List, int, int)}, with the turn's category names as the tools' category enum. */
    public Reply call(List<Message> messages, int maxOutputTokens, int toolResults, List<String> categories) {
        return send(messages, maxOutputTokens, tools.callbacks(categories), ROLE, toolResults,
                promptCaching() == PromptCaching.ON);
    }

    /**
     * A call with the main role and no tools: the conversation summary ({@value #SUMMARY_ROLE}) and the monthly summary
     * ({@value #MONTHLY_ROLE}), each with its own role in the AI-06 line (§8).
     *
     * @param role  the log line's role
     * @param items the log line's items: turns folded, or records sent
     */
    public Reply complete(List<Message> messages, int maxOutputTokens, String role, int items) {
        return send(messages, maxOutputTokens, List.of(), role, items, false);
    }

    private Reply send(List<Message> messages, int maxOutputTokens, List<ToolCallback> callbacks, String role,
                       int toolResults, boolean cache) {
        ChatProvider chat = providers.chat();
        if (!providers.usable() || chat == null) {
            throw new IllegalStateException("AI is off");
        }
        ChatModel model = chat.model();
        String modelId = chat.modelId();
        ChatOptions callOptions = options.options(model, maxOutputTokens, callbacks);
        boolean cached = false;
        if (cache) {
            int system = (int) messages.stream().filter(m -> m.getMessageType() == MessageType.SYSTEM).count();
            try {
                callOptions = chat.promptCache().orElseThrow().withCachePoints(callOptions, system,
                        Math.min(CACHED_SYSTEM_MESSAGES, system));
                cached = true;
            } catch (IllegalArgumentException e) {
                // a layout the provider can't mark (no system message, or more after the context block than the
                // summary): this call goes uncached
            }
        }
        long start = System.nanoTime();
        ChatResponse response;
        try {
            response = model.call(new Prompt(messages, callOptions));
        } catch (RuntimeException e) {
            AiCallLog.record(role, modelId, toolResults, null, null, millisSince(start), AiCallLog.Outcome.ERROR, e);
            if (cached && chat.promptCache().orElseThrow().refusedCaching(e)) {
                refusedCaching.add(modelId);
                log.warn("prompt cache refused by the provider for model={}; its chat calls go uncached", modelId);
                return send(messages, maxOutputTokens, callbacks, role, toolResults, false);
            }
            throw e;
        }
        long millis = millisSince(start);
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        boolean reported = reported(usage);
        Integer input = reported ? usage.getPromptTokens() : null;
        Integer output = reported ? usage.getCompletionTokens() : null;
        int cacheRead = reported ? tokens(usage.getCacheReadInputTokens()) : 0;
        int cacheWrite = reported ? tokens(usage.getCacheWriteInputTokens()) : 0;

        List<Generation> generations = response == null ? List.of() : response.getResults().stream()
                .filter(g -> g != null && g.getOutput() != null).toList();
        if (generations.isEmpty()) {
            InvalidReplyException invalid = new InvalidReplyException();
            AiCallLog.record(role, modelId, toolResults, input, output, cacheRead, cacheWrite, millis,
                    AiCallLog.Outcome.INVALID_OUTPUT, invalid);
            throw invalid;
        }
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        boolean cut = false;
        AssistantMessage carrier = null;
        for (Generation generation : generations) {
            AssistantMessage message = generation.getOutput();
            calls.addAll(message.getToolCalls());
            if (message.getText() != null && !message.getText().isBlank()) {
                texts.add(message.getText());
            }
            if (carrier == null && message.hasToolCalls()) {
                carrier = message;
            }
            cut |= isCut(generation);
        }
        String text = String.join("\n", texts);
        AssistantMessage message;
        if (generations.size() == 1) {
            message = generations.getFirst().getOutput();
        } else {
            AssistantMessage base = Objects.requireNonNullElse(carrier, generations.getLast().getOutput());
            message = base.mutate().content(text).toolCalls(calls).build();
        }
        AiCallLog.record(role, modelId, toolResults, input, output, cacheRead, cacheWrite, millis,
                cut && calls.isEmpty() ? AiCallLog.Outcome.LIMIT : AiCallLog.Outcome.OK, null);
        return new Reply(message, calls, text, cut && calls.isEmpty(), input, output, cacheRead, cacheWrite, cached,
                millis);
    }

    /** The model stopped at the output cap: Converse says {@code max_tokens}, other providers {@code length}. */
    private static boolean isCut(Generation generation) {
        String reason = generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason();
        if (reason == null) {
            return false;
        }
        String r = reason.toLowerCase(Locale.ROOT);
        return r.equals("max_tokens") || r.equals("length");
    }

    /**
     * Whether the provider reported usage. Spring AI stands in {@link EmptyUsage} (zeros) when it has none; a call that
     * sent a prompt never used zero input tokens, so zero input (cached prefix included) means "not reported", never
     * "free".
     */
    static boolean reported(Usage usage) {
        if (usage == null || usage instanceof EmptyUsage) {
            return false;
        }
        Integer input = usage.getPromptTokens();
        return input != null && input >= 0 && usage.getCompletionTokens() != null
                && (long) input + tokens(usage.getCacheReadInputTokens())
                + tokens(usage.getCacheWriteInputTokens()) > 0;
    }

    private static int tokens(Long count) {
        return count == null || count < 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, count);
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** The model returned no message at all. Counts nothing, quotes nothing. */
    public static final class InvalidReplyException extends IllegalStateException {

        InvalidReplyException() {
            super("The chat model returned no message");
        }
    }
}
