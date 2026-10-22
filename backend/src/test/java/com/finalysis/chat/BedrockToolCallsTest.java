package com.finalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.finalysis.ai.AiMode;
import com.finalysis.ai.AiPrices;
import com.finalysis.ai.AiProperties;
import com.finalysis.ai.ChatRoleOptions;
import com.finalysis.ai.ChatRoleProperties;
import com.finalysis.ai.PromptCache;
import com.finalysis.ai.bedrock.BedrockClientConfiguration;
import com.finalysis.chat.tool.ToolRegistryTestSupport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 5's precondition (docs/plans/phase-6.md §10.1): with Spring AI's real Bedrock Converse model (2.0.1) and the
 * chat tools' refusing callbacks in the options, a tool call the model returns comes back to the caller unexecuted,
 * and the next request carries the caller's tool results. The Bedrock client is the app's own
 * ({@link BedrockClientConfiguration#client}) over an in-process HTTP client that answers with canned Converse
 * responses and records every request: nothing leaves the machine, and the credentials are dummies.
 *
 * <p>Calls go through {@link ChatModelClient}, which calls {@code ChatModel.call(Prompt)} with no {@code ChatClient}, so
 * no advisor that could run tools. Two findings shaped it: called directly, the Converse model requires its own options
 * type (a plain {@code ToolCallingChatOptions} is a {@code ClassCastException}), so the role's options are merged onto
 * the model's; and it returns one Converse message as two generations (text, then tool calls), so the client gathers
 * them into one assistant message.
 */
class BedrockToolCallsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Answers each request with the next canned body (status 200) and keeps every request body. */
    static final class ScriptedConverseHttpClient implements SdkHttpClient {

        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final List<String> paths = new CopyOnWriteArrayList<>();
        private final Deque<String> bodies;

        ScriptedConverseHttpClient(String... bodies) {
            this.bodies = new ArrayDeque<>(List.of(bodies));
        }

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() {
                    paths.add(request.httpRequest().encodedPath());
                    try (InputStream in = request.contentStreamProvider().orElseThrow().newStream()) {
                        requests.add(JSON.readTree(in.readAllBytes()));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    String body = bodies.poll();
                    if (body == null) {
                        throw new AssertionError("no more canned responses: an unexpected request was sent");
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    return HttpExecuteResponse.builder()
                            .response(SdkHttpResponse.builder().statusCode(200)
                                    .headers(Map.of("Content-Type", List.of("application/json")))
                                    .build())
                            .responseBody(AbortableInputStream.create(new ByteArrayInputStream(bytes)))
                            .build();
                }

                @Override
                public void abort() {
                }
            };
        }

        @Override
        public String clientName() {
            return "scripted-converse";
        }

        @Override
        public void close() {
        }
    }

    private static final String TOOL_USE = """
            {"output": {"message": {"role": "assistant", "content": [
                {"text": "Let me look."},
                {"toolUse": {"toolUseId": "tooluse_1", "name": "get_month_totals", "input": {"month": "2026-08"}}},
                {"toolUse": {"toolUseId": "tooluse_2", "name": "dismiss_alert", "input": {"key": "x"}}}]}},
             "stopReason": "tool_use",
             "usage": {"inputTokens": 1200, "outputTokens": 40, "totalTokens": 1240},
             "metrics": {"latencyMs": 5}}
            """;

    private static final String FINAL_TEXT = """
            {"output": {"message": {"role": "assistant", "content": [{"text": "You spent a lot [t1]."}]}},
             "stopReason": "end_turn",
             "usage": {"inputTokens": 1500, "outputTokens": 12, "totalTokens": 1512},
             "metrics": {"latencyMs": 5}}
            """;

    /** As the aws profile builds it: Spring AI's model over the app's Bedrock client, with temperature 0.0 by default. */
    static BedrockProxyChatModel model(SdkHttpClient http) {
        return BedrockProxyChatModel.builder()
                .bedrockRuntimeClient(BedrockClientConfiguration.client(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key-id", "test-secret")),
                        Region.US_EAST_1, Duration.ofSeconds(30), http))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-key-id", "test-secret")))
                .region(Region.US_EAST_1)
                .options(BedrockChatOptions.builder().model("us.anthropic.claude-haiku-4-5-20251001-v1:0")
                        .temperature(0.0).maxTokens(2000).build())
                .toolCallingManager(ToolCallingManager.builder().build())
                .build();
    }

    private static ChatModelClient client(ChatModel model, Double temperature) {
        return client(model, temperature, new AiPrices(Map.of()), null);
    }

    /** The client over this model; {@code cache} null is a provider without cache points. */
    static ChatModelClient client(ChatModel model, Double temperature, AiPrices prices, PromptCache cache) {
        AiProperties ai = new AiProperties(AiMode.CLOUD, "bedrock",
                new AiProperties.Roles("test-main-model", "test-categorization-model", "test-embedding-model", ""),
                true);
        ChatRoleProperties role = new ChatRoleProperties(temperature, true);
        return new ChatModelClient(new StaticListableBeanFactory(Map.of("chatModel", model))
                .getBeanProvider(ChatModel.class), ai, new ChatRoleOptions(ai, role),
                new ChatToolCallbacks(ToolRegistryTestSupport.registry()), role, prices,
                new StaticListableBeanFactory(cache == null ? Map.of() : Map.of("promptCache", cache))
                        .getBeanProvider(PromptCache.class));
    }

    private static List<Message> question() {
        return List.of(new SystemMessage("system"), new UserMessage("<question>\"How much in August?\"</question>"));
    }

    @Test
    void aToolCallComesBackUnexecuted() {
        ScriptedConverseHttpClient http = new ScriptedConverseHttpClient(TOOL_USE);

        ChatModelClient.Reply reply = client(model(http), null).call(question(), 1500, 0);

        // One request, and the reply is the model's tool calls as sent: nothing ran (a refusing callback would have
        // thrown ToolExecutedByFrameworkException), and no second request was made.
        assertThat(http.requests).hasSize(1);
        assertThat(http.paths.getFirst()).endsWith("/converse");
        // Bedrock's mapping puts the text and the tool calls in two generations; the client gathers them.
        assertThat(reply.toolCalls()).extracting(AssistantMessage.ToolCall::id, AssistantMessage.ToolCall::name)
                .containsExactly(tuple("tooluse_1", "get_month_totals"), tuple("tooluse_2", "dismiss_alert"));
        assertThat(JSON.readTree(reply.toolCalls().getFirst().arguments()).get("month").stringValue())
                .isEqualTo("2026-08");
        assertThat(reply.message().getToolCalls()).hasSize(2);
        assertThat(reply.message().getText()).isEqualTo("Let me look.");
        assertThat(reply.inputTokens()).isEqualTo(1200);
        assertThat(reply.outputTokens()).isEqualTo(40);
        assertThat(reply.cut()).isFalse();

        // The request carried the registry's ten definitions and the main model, and no temperature (§2.3).
        JsonNode request = http.requests.getFirst();
        JsonNode tools = request.get("toolConfig").get("tools");
        assertThat(tools.valueStream().map(t -> t.get("toolSpec").get("name").stringValue()).toList())
                .containsExactlyElementsOf(ToolRegistryTestSupport.registry().definitions().stream()
                        .map(d -> d.name()).toList());
        assertThat(http.paths.getFirst()).contains("test-main-model");
        assertThat(request.get("inferenceConfig").get("maxTokens").asInt()).isEqualTo(1500);
        assertThat(request.get("inferenceConfig").has("temperature")).isFalse();
    }

    @Test
    void aConfiguredTemperatureIsSent() {
        ScriptedConverseHttpClient http = new ScriptedConverseHttpClient(FINAL_TEXT);

        client(model(http), 0.3).call(question(), 1500, 0);

        assertThat(http.requests.getFirst().get("inferenceConfig").get("temperature").asDouble()).isEqualTo(0.3);
    }

    @Test
    void theCallersToolResultsGoBackAsToolResultBlocks() {
        ScriptedConverseHttpClient http = new ScriptedConverseHttpClient(TOOL_USE, FINAL_TEXT);
        ChatModelClient client = client(model(http), null);
        List<Message> messages = new ArrayList<>(question());

        ChatModelClient.Reply calls = client.call(messages, 1500, 0);
        messages.add(calls.message());
        messages.add(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("tooluse_1", "get_month_totals", "{\"result\":{\"ref\":\"t1\"}}"),
                new ToolResponseMessage.ToolResponse("tooluse_2", "dismiss_alert", "{\"error\":\"NOT_A_TOOL\"}")))
                .build());
        ChatModelClient.Reply answer = client.call(messages, 1500, 2);

        assertThat(answer.toolCalls()).isEmpty();
        assertThat(answer.text()).isEqualTo("You spent a lot [t1].");
        assertThat(http.requests).hasSize(2);
        JsonNode sent = http.requests.get(1).get("messages");
        // user, assistant (its text and both toolUse blocks), user (both toolResult blocks, in order, with their ids)
        assertThat(sent).hasSize(3);
        JsonNode assistant = sent.get(1);
        assertThat(assistant.get("role").stringValue()).isEqualTo("assistant");
        assertThat(assistant.get("content").valueStream().filter(c -> c.has("text"))
                .map(c -> c.get("text").stringValue()).toList()).containsExactly("Let me look.");
        assertThat(assistant.get("content").valueStream().filter(c -> c.has("toolUse"))
                .map(c -> c.get("toolUse").get("toolUseId").stringValue()).toList())
                .containsExactly("tooluse_1", "tooluse_2");
        JsonNode results = sent.get(2);
        assertThat(results.get("role").stringValue()).isEqualTo("user");
        assertThat(results.get("content").valueStream().map(c -> c.get("toolResult").get("toolUseId").stringValue())
                .toList()).containsExactly("tooluse_1", "tooluse_2");
        assertThat(results.get("content").get(0).get("toolResult").toString()).contains("t1");
    }
}
