package com.finalysis.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ai.ollama.OllamaProviderFactory;
import com.finalysis.chat.ChatModelClient;
import com.finalysis.chat.ChatToolCallbacks;
import com.finalysis.chat.tool.ToolRegistryTestSupport;
import com.finalysis.support.NoCloudAiGuard;
import com.finalysis.support.OllamaStub;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Ollama providers of the Self-hosted and Local paths (docs/plans/phase-9.md §3.2, §4.4) against a stub server on
 * a random loopback port: a tool call comes back unexecuted, a malformed one fails the call, a timeout, a 500 and a
 * missing model fail it once (no retries), embeddings are checked like Titan's, and the reachability probe reads the
 * version and the pulled models. Every request body the stub receives is read field by field. The categorization call
 * is {@code OllamaCategorizationTest}'s, a whole chat turn {@code OllamaTurnPayloadTest}'s.
 */
class OllamaProvidersTest {

    static final String CHAT_MODEL = "test-ollama-chat";
    static final String EMBEDDING_MODEL = "nomic-embed-text";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OllamaStub stub;

    @BeforeEach
    void start() {
        stub = OllamaStub.start(CHAT_MODEL + ":latest", EMBEDDING_MODEL + ":latest");
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    static OllamaProviderFactory factory() {
        return new OllamaProviderFactory(new StaticListableBeanFactory(
                Map.of("noRealOllama", new NoCloudAiGuard.StubOnlyOllamaHosts())).getBeanProvider(OllamaHostGuard.class));
    }

    private ProviderSet providers(URI base, Duration readTimeout) {
        return factory().create(base, new OllamaProperties.Models(CHAT_MODEL, EMBEDDING_MODEL), 768, readTimeout);
    }

    private ChatModelClient chat(ProviderSet set) {
        ActiveProviders local = ActiveProviders.fixed(AiPath.LOCAL, set);
        ChatRoleProperties role = new ChatRoleProperties(null, true);
        return new ChatModelClient(local, new ChatRoleOptions(local, role),
                new ChatToolCallbacks(ToolRegistryTestSupport.registry()), role, new AiPrices(Map.of()));
    }

    private static List<Message> question() {
        return List.of(new SystemMessage("system"), new UserMessage("<question>\"How much in March?\"</question>"));
    }

    // ------------------------------------------------------------------ chat

    /** The framework never runs a tool: the call comes back as the model sent it, and nothing else is requested. */
    @Test
    void aToolCallComesBackUnexecuted() {
        stub.then(OllamaStub.toolCalls("get_month_totals", "{\"from\": \"2026-03\", \"to\": \"2026-03\"}",
                "dismiss_alert", "{\"key\": \"x\"}"));

        ChatModelClient.Reply reply = chat(providers(stub.baseUrl(), Duration.ofSeconds(5))).call(question(), 1500, 0);

        assertThat(reply.toolCalls()).extracting(AssistantMessage.ToolCall::name)
                .containsExactly("get_month_totals", "dismiss_alert");
        assertThat(JSON.readTree(reply.toolCalls().getFirst().arguments()).get("from").stringValue())
                .isEqualTo("2026-03");
        assertThat(reply.inputTokens()).isEqualTo(150);
        assertThat(reply.outputTokens()).isEqualTo(25);
        assertThat(stub.requests()).extracting(OllamaStub.Request::path).containsExactly("/api/chat");
    }

    /** What one chat round sends, field by field: the model, the messages, the tools, the output cap; nothing else. */
    @Test
    void aChatRoundSendsTheModelTheMessagesTheToolsAndTheCap() {
        stub.then(OllamaStub.text("You spent $10.00 [c1]."));

        chat(providers(stub.baseUrl(), Duration.ofSeconds(5))).call(question(), 1500, 0);

        JsonNode body = stub.chatBodies().getFirst();
        assertThat(OllamaStub.fields(body)).containsOnly("model", "messages", "stream", "tools", "options");
        assertThat(body.get("model").stringValue()).isEqualTo(CHAT_MODEL);
        assertThat(body.get("stream").asBoolean()).isFalse();
        assertThat(body.get("messages").valueStream().map(m -> m.get("role").stringValue()))
                .containsExactly("system", "user");
        assertThat(body.get("messages").get(1).get("content").stringValue())
                .isEqualTo("<question>\"How much in March?\"</question>");
        assertThat(body.get("tools").valueStream().map(t -> t.get("function").get("name").stringValue()).toList())
                .containsExactlyInAnyOrderElementsOf(ToolRegistryTestSupport.registry().definitions().stream()
                        .map(d -> d.name()).toList());
        assertThat(body.get("options").get("num_predict").asInt()).isEqualTo(1500);
        assertThat(body.get("options").has("temperature")).as("no temperature unless configured").isFalse();
        assertThat(body.has("format")).as("chat asks for no structured output").isFalse();
    }

    /** A tool call whose arguments aren't an object fails the call; the loop treats it like any failed call. */
    @Test
    void aMalformedToolCallFailsTheCall() {
        stub.then(OllamaStub.raw("""
                {"model": "stub", "created_at": "2026-10-29T12:00:00Z", "done": true,
                 "message": {"role": "assistant", "content": "",
                             "tool_calls": [{"function": {"name": "get_month_totals", "arguments": "not json"}}]}}"""));

        assertThatThrownBy(() -> chat(providers(stub.baseUrl(), Duration.ofSeconds(5))).call(question(), 1500, 0))
                .isInstanceOf(RuntimeException.class);
        assertThat(stub.requests("/api/chat")).hasSize(1);
    }

    /** A slow server fails the call at the path's read timeout, once: no retries. */
    @Test
    void aTimeoutFailsTheCallOnce() {
        stub.then(OllamaStub.delayed(OllamaStub.text("late"), 3_000));
        long start = System.nanoTime();

        assertThatThrownBy(() -> chat(providers(stub.baseUrl(), Duration.ofMillis(300))).call(question(), 1500, 0))
                .isInstanceOf(RuntimeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(stub.requests("/api/chat")).hasSize(1);
    }

    @Test
    void aServerErrorFailsTheCallOnce() {
        stub.then(OllamaStub.status(500, "boom"), OllamaStub.text("a retry would get this"));

        assertThatThrownBy(() -> chat(providers(stub.baseUrl(), Duration.ofSeconds(5))).call(question(), 1500, 0))
                .isInstanceOf(RuntimeException.class);
        assertThat(stub.requests("/api/chat")).hasSize(1);
    }

    /** A model that isn't pulled is a 404; the app never pulls it (the probe reports it instead). */
    @Test
    void aMissingModelFailsTheCallAndIsNeverPulled() {
        stub.then(OllamaStub.status(404, "model \"test-ollama-chat\" not found, try pulling it first"));

        assertThatThrownBy(() -> chat(providers(stub.baseUrl(), Duration.ofSeconds(5))).call(question(), 1500, 0))
                .isInstanceOf(RuntimeException.class);
        assertThat(stub.requests()).extracting(OllamaStub.Request::path).containsExactly("/api/chat");
    }

    /** Ollama has no prompt cache: its providers report none, so chat never asks for one. */
    @Test
    void ollamaHasNoPromptCache() {
        ProviderSet set = providers(stub.baseUrl(), Duration.ofSeconds(5));

        assertThat(set.provider()).isEqualTo("ollama");
        assertThat(set.chat().promptCache()).isEmpty();
        assertThat(set.chat().modelId()).isEqualTo(CHAT_MODEL);
        assertThat(set.categorization().modelId()).isEqualTo(CHAT_MODEL);
        assertThat(set.categorization().nativeStructuredOutput()).isTrue();
        assertThat(chat(set).promptCaching()).isNotEqualTo(ChatModelClient.PromptCaching.ON);
        assertThat(chat(set).price()).as("no token price off Cloud").isEmpty();
    }

    // ------------------------------------------------------------------ embeddings

    @Test
    void anEmbeddingIsOneTextWithTheModelAndItsLengthChecked() {
        ProviderSet set = providers(stub.baseUrl(), Duration.ofSeconds(5));
        SpringAiTextEmbedder embedder = new SpringAiTextEmbedder(ActiveProviders.fixed(AiPath.LOCAL, set));

        TextEmbedder.Embedding embedding = embedder.embed("QUORVANE BAKEHOUSE");

        assertThat(embedding.vector()).hasSize(768);
        assertThat(embedder.modelId()).isEqualTo(EMBEDDING_MODEL);
        assertThat(embedder.dimensions()).isEqualTo(768);
        JsonNode body = stub.requests("/api/embed").getFirst().json();
        assertThat(body.get("model").stringValue()).isEqualTo(EMBEDDING_MODEL);
        assertThat(body.get("input").valueStream().map(JsonNode::stringValue)).containsExactly("QUORVANE BAKEHOUSE");

        stub.dimensions(1024);
        assertThatThrownBy(() -> embedder.embed("QUORVANE BAKEHOUSE"))
                .isInstanceOf(TextEmbedder.InvalidEmbeddingException.class)
                .hasMessage("The embedding model returned 1024 dimensions, expected 768");
    }

    // ------------------------------------------------------------------ the probe and the guard

    @Test
    void theProbeReadsTheVersionAndThePulledModels() {
        OllamaClients.Probe probe = factory().probe(stub.baseUrl(), Duration.ofSeconds(1));

        assertThat(probe.version()).isEqualTo(OllamaStub.VERSION);
        assertThat(probe.models()).containsExactly(CHAT_MODEL + ":latest", EMBEDDING_MODEL + ":latest");
        assertThat(stub.requests()).extracting(OllamaStub.Request::path).containsExactly("/api/version", "/api/tags");
    }

    @Test
    void aModelCountsAsPulledWithOrWithoutItsLatestTag() {
        assertThat(AiProviders.pulled("nomic-embed-text", List.of("nomic-embed-text:latest"))).isTrue();
        assertThat(AiProviders.pulled("ministral-3:14b", List.of("ministral-3:14b"))).isTrue();
        assertThat(AiProviders.pulled("ministral-3:14b", List.of("ministral-3:8b"))).isFalse();
        assertThat(AiProviders.pulled(OllamaProperties.UNSET_CHAT_MODEL, List.of("nomic-embed-text:latest")))
                .isFalse();
    }

    /** The suite's guard: a client or probe for anything but a registered stub on loopback fails the test. */
    @Test
    void aClientForAnyOtherHostFailsTheTest() {
        URI unregistered = URI.create("http://127.0.0.1:" + (stub.port() == 65_000 ? 65_001 : 65_000));
        URI server = URI.create("http://finalysis-selfhost:11434");

        assertThatThrownBy(() -> providers(unregistered, Duration.ofSeconds(1)))
                .isInstanceOf(NoCloudAiGuard.OllamaCallBlockedError.class);
        assertThatThrownBy(() -> providers(server, Duration.ofSeconds(1)))
                .isInstanceOf(NoCloudAiGuard.OllamaCallBlockedError.class);
        assertThatThrownBy(() -> factory().probe(server, Duration.ofSeconds(1)))
                .isInstanceOf(NoCloudAiGuard.OllamaCallBlockedError.class);
        assertThat(stub.requests()).isEmpty();
    }
}
