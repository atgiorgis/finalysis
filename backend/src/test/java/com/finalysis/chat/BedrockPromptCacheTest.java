package com.finalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ai.AiPrices;
import com.finalysis.ai.bedrock.BedrockPromptCache;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prompt caching of chat's fixed prefix (docs/plans/phase-6.md step 8c) with Spring AI's real Bedrock Converse model
 * over an in-process HTTP client (as {@link BedrockToolCallsTest}; nothing leaves the machine): where the cache points
 * go in the request Bedrock would receive, how the cached tokens come back, and that a model without cache prices, the
 * summaries' calls, and a model whose cache points Bedrock refuses all run uncached.
 */
class BedrockPromptCacheTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final AiPrices CACHE_PRICED = new AiPrices(Map.of("test-main-model", new AiPrices.Price(
            new BigDecimal("1.00"), new BigDecimal("5.00"), new BigDecimal("0.10"), new BigDecimal("1.25"))));
    private static final AiPrices NO_CACHE_PRICES = new AiPrices(Map.of("test-main-model", new AiPrices.Price(
            new BigDecimal("1.00"), new BigDecimal("5.00"))));

    private static final String CACHED_ANSWER = """
            {"output": {"message": {"role": "assistant", "content": [{"text": "No figures here."}]}},
             "stopReason": "end_turn",
             "usage": {"inputTokens": 420, "outputTokens": 12, "totalTokens": 6352,
                       "cacheReadInputTokens": 5920, "cacheWriteInputTokens": 0},
             "metrics": {"latencyMs": 5}}
            """;

    private static final String PLAIN_ANSWER = """
            {"output": {"message": {"role": "assistant", "content": [{"text": "No figures here."}]}},
             "stopReason": "end_turn",
             "usage": {"inputTokens": 6340, "outputTokens": 12, "totalTokens": 6352},
             "metrics": {"latencyMs": 5}}
            """;

    private static final String CACHE_REFUSED = """
            {"message": "The model does not support prompt caching (cachePoint)."}
            """;

    /** Answers each request with the next canned status and body, and keeps every request body. */
    static final class Http implements SdkHttpClient {

        record Canned(int status, String errorType, String body) {
        }

        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        private final Deque<Canned> responses;

        Http(Canned... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        static Canned ok(String body) {
            return new Canned(200, null, body);
        }

        static Canned refused() {
            return new Canned(400, "ValidationException", CACHE_REFUSED);
        }

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() {
                    try (InputStream in = request.contentStreamProvider().orElseThrow().newStream()) {
                        requests.add(JSON.readTree(in.readAllBytes()));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    Canned canned = responses.poll();
                    if (canned == null) {
                        throw new AssertionError("no more canned responses: an unexpected request was sent");
                    }
                    SdkHttpResponse.Builder response = SdkHttpResponse.builder().statusCode(canned.status())
                            .putHeader("Content-Type", "application/json");
                    if (canned.errorType() != null) {
                        response.putHeader("x-amzn-ErrorType", canned.errorType());
                    }
                    return HttpExecuteResponse.builder().response(response.build())
                            .responseBody(AbortableInputStream.create(new ByteArrayInputStream(
                                    canned.body().getBytes(StandardCharsets.UTF_8))))
                            .build();
                }

                @Override
                public void abort() {
                }
            };
        }

        @Override
        public String clientName() {
            return "canned-converse";
        }

        @Override
        public void close() {
        }
    }

    /** A round as {@link ChatPrompt#start} lays it out: system prompt, context block, summary, history, question. */
    private static List<Message> round(boolean summary) {
        List<Message> messages = new java.util.ArrayList<>(List.of(new SystemMessage("the system prompt"),
                new SystemMessage("<context>{\"today\":\"2026-10-15\"}</context>")));
        if (summary) {
            messages.add(new SystemMessage("<summary>\"they asked about dining\"</summary>"));
            messages.add(new UserMessage("<question>\"And in July?\"</question>"));
            messages.add(AssistantMessage.builder().content("July's dining is below.").build());
        }
        messages.add(new UserMessage("<question>\"How much in August?\"</question>"));
        return messages;
    }

    private static List<String> systemBlocks(JsonNode request) {
        return request.get("system").valueStream().map(b -> b.has("cachePoint") ? "CACHE POINT"
                : b.get("text").stringValue()).toList();
    }

    private static boolean anyCachePoint(JsonNode node) {
        return node.toString().contains("cachePoint");
    }

    @Test
    void theToolsTheSystemPromptAndTheContextBlockAreCachedAndTheSummaryIsNot() {
        Http http = new Http(Http.ok(CACHED_ANSWER));
        ChatModelClient client = BedrockToolCallsTest.client(BedrockToolCallsTest.model(http), null, CACHE_PRICED,
                new BedrockPromptCache());
        assertThat(client.promptCaching()).isEqualTo(ChatModelClient.PromptCaching.ON);

        ChatModelClient.Reply reply = client.call(round(true), 1500, 0);

        JsonNode request = http.requests.getFirst();
        // tools first: the last tool definition is followed by a cache point
        JsonNode tools = request.get("toolConfig").get("tools");
        assertThat(tools.get(tools.size() - 1).has("cachePoint")).isTrue();
        assertThat(tools.valueStream().filter(t -> t.has("cachePoint")).count()).isEqualTo(1);
        // then the system blocks: the cached prefix ends after the context block, before the summary
        assertThat(systemBlocks(request)).containsExactly("the system prompt",
                "<context>{\"today\":\"2026-10-15\"}</context>", "CACHE POINT",
                "<summary>\"they asked about dining\"</summary>");
        assertThat(request.get("system").get(2).get("cachePoint").get("type").stringValue()).isEqualTo("default");
        // the conversation itself is never cached
        assertThat(anyCachePoint(request.get("messages"))).isFalse();

        assertThat(reply.cached()).isTrue();
        assertThat(reply.inputTokens()).isEqualTo(420);
        assertThat(reply.cacheReadTokens()).isEqualTo(5920);
        assertThat(reply.cacheWriteTokens()).isZero();
    }

    @Test
    void withoutASummaryThePointStillEndsAfterTheContextBlock() {
        Http http = new Http(Http.ok(CACHED_ANSWER));
        ChatModelClient client = BedrockToolCallsTest.client(BedrockToolCallsTest.model(http), null, CACHE_PRICED,
                new BedrockPromptCache());

        client.call(round(false), 1500, 0);

        assertThat(systemBlocks(http.requests.getFirst())).containsExactly("the system prompt",
                "<context>{\"today\":\"2026-10-15\"}</context>", "CACHE POINT");
        assertThat(anyCachePoint(http.requests.getFirst().get("messages"))).isFalse();
    }

    @Test
    void aModelWithoutCachePricesRunsUncached() {
        Http http = new Http(Http.ok(PLAIN_ANSWER));
        ChatModelClient client = BedrockToolCallsTest.client(BedrockToolCallsTest.model(http), null, NO_CACHE_PRICES,
                new BedrockPromptCache());
        assertThat(client.promptCaching()).isEqualTo(ChatModelClient.PromptCaching.OFF_NO_CACHE_PRICES);

        ChatModelClient.Reply reply = client.call(round(true), 1500, 0);

        assertThat(anyCachePoint(http.requests.getFirst())).isFalse();
        assertThat(reply.cached()).isFalse();
        assertThat(reply.inputTokens()).isEqualTo(6340);
        assertThat(reply.cacheReadTokens()).isZero();
    }

    @Test
    void aProviderWithoutCachePointsRunsUncached() {
        Http http = new Http(Http.ok(PLAIN_ANSWER));
        ChatModelClient client = BedrockToolCallsTest.client(BedrockToolCallsTest.model(http), null, CACHE_PRICED,
                null);

        assertThat(client.promptCaching()).isEqualTo(ChatModelClient.PromptCaching.OFF_PROVIDER);
        client.call(round(false), 1500, 0);
        assertThat(anyCachePoint(http.requests.getFirst())).isFalse();
    }

    @Test
    void theSummariesCallsAreNeverCached() {
        Http http = new Http(Http.ok(PLAIN_ANSWER));
        ChatModelClient client = BedrockToolCallsTest.client(BedrockToolCallsTest.model(http), null, CACHE_PRICED,
                new BedrockPromptCache());

        client.complete(List.of(new SystemMessage("summarize"), new UserMessage("the turns")), 400,
                ChatModelClient.SUMMARY_ROLE, 3);

        assertThat(anyCachePoint(http.requests.getFirst())).isFalse();
    }

    @Test
    void cachePointsBedrockRefusesAreDroppedAndTheCallIsSentAgainUncached() {
        Http http = new Http(Http.refused(), Http.ok(PLAIN_ANSWER), Http.ok(PLAIN_ANSWER));
        ChatModelClient client = BedrockToolCallsTest.client(BedrockToolCallsTest.model(http), null, CACHE_PRICED,
                new BedrockPromptCache());

        ChatModelClient.Reply reply = client.call(round(false), 1500, 0);

        assertThat(http.requests).hasSize(2);
        assertThat(anyCachePoint(http.requests.get(0))).isTrue();
        assertThat(anyCachePoint(http.requests.get(1))).isFalse();
        assertThat(reply.text()).isEqualTo("No figures here.");
        assertThat(reply.cached()).isFalse();
        assertThat(client.promptCaching()).isEqualTo(ChatModelClient.PromptCaching.OFF_REFUSED);

        // the model stays uncached: no second refused call
        client.call(round(false), 1500, 0);
        assertThat(http.requests).hasSize(3);
        assertThat(anyCachePoint(http.requests.get(2))).isFalse();
    }

    @Test
    void anotherValidationErrorIsNotTakenForARefusedCache() {
        BedrockPromptCache cache = new BedrockPromptCache();
        assertThat(cache.refusedCaching(new IllegalStateException("cache"))).isFalse();
        assertThat(cache.refusedCaching(new RuntimeException(software.amazon.awssdk.services.bedrockruntime.model
                .ValidationException.builder().message("max_tokens is too large").build()))).isFalse();
        assertThat(cache.refusedCaching(new RuntimeException(software.amazon.awssdk.services.bedrockruntime.model
                .ValidationException.builder().message("cachePoint is not supported").build()))).isTrue();
    }

    @Test
    void aPointAnywhereElseIsRefusedBeforeAnyCall() {
        BedrockPromptCache cache = new BedrockPromptCache();
        BedrockChatOptions options = BedrockChatOptions.builder().model("m").build();
        assertThatThrownBy(() -> cache.withCachePoints(options, 4, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.withCachePoints(options, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(((BedrockChatOptions) cache.withCachePoints(options, 3, 2)).getCacheOptions()
                .isMultiBlockSystemCaching()).isTrue();
        assertThat(((BedrockChatOptions) cache.withCachePoints(options, 2, 2)).getCacheOptions()
                .isMultiBlockSystemCaching()).isFalse();
    }
}
