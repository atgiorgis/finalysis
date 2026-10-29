package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.ai.ActiveProviders;
import com.finalysis.ai.AiPath;
import com.finalysis.ai.OllamaHostGuard;
import com.finalysis.ai.OllamaProperties;
import com.finalysis.ai.ProviderSet;
import com.finalysis.ai.ollama.OllamaProviderFactory;
import com.finalysis.ai.outbound.OutboundPayloadGuard;
import com.finalysis.ai.outbound.Redactor;
import com.finalysis.ai.outbound.RedactionContext;
import com.finalysis.categorize.CategorizationModelClient.Result;
import com.finalysis.categorize.CategorizationPromptBuilder.Candidate;
import com.finalysis.categorize.CategorizationPromptBuilder.CategorizationPrompt;
import com.finalysis.support.NoCloudAiGuard;
import com.finalysis.support.OllamaStub;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;

/**
 * Categorization on the Ollama paths (docs/plans/phase-9.md §3.2, §4.4) against a stub server: the structured output
 * is Ollama's JSON-schema {@code format}, a valid answer is parsed, an invalid one is {@code INVALID_OUTPUT} (null),
 * never trusted. The request body, read field by field, carries exactly the prompt the Bedrock path sends
 * ({@link OutboundPayloadTest}): the system prompt and the redacted merchant text and amounts, nothing else.
 */
class OllamaCategorizationTest {

    private static final String MODEL = "test-ollama-chat";
    private static final String ANSWER = """
            {"answers": [{"key": "i1", "category": "Dining", "confidence": 0.92},
                         {"key": "i2", "category": "Groceries", "confidence": 0.3}]}""";

    private final CategorizationPrompt prompt = new CategorizationPromptBuilder(
            new Redactor(MerchantCleaningRules.loadDefault()), new OutboundPayloadGuard())
            .build(List.of("Dining", "Groceries"), List.of(
                    new Candidate("BRAMBLEWICK NOODLE HOUSE", null, new BigDecimal("-18.40"), List.of()),
                    new Candidate("QUILLFEATHER MARKET", null, new BigDecimal("-64.02"), List.of())),
                    RedactionContext.EMPTY);

    private OllamaStub stub;
    private CategorizationModelClient client;

    @BeforeEach
    void start() {
        stub = OllamaStub.start(MODEL + ":latest", "nomic-embed-text:latest");
        ProviderSet set = new OllamaProviderFactory(new StaticListableBeanFactory(Map.of("guard",
                new NoCloudAiGuard.StubOnlyOllamaHosts())).getBeanProvider(OllamaHostGuard.class))
                .create(stub.baseUrl(), new OllamaProperties.Models(MODEL, "nomic-embed-text"), 768,
                        Duration.ofSeconds(5));
        client = new CategorizationModelClient(ActiveProviders.fixed(AiPath.LOCAL, set));
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void aStructuredAnswerIsParsed() {
        stub.then(OllamaStub.text(ANSWER));

        Result result = client.categorize(prompt);

        assertThat(result.parsed()).isTrue();
        assertThat(result.response().answers()).containsExactly(
                new CategorizationResponse.Answer("i1", "Dining", 0.92),
                new CategorizationResponse.Answer("i2", "Groceries", 0.3));
        assertThat(result.inputTokens()).isEqualTo(120);
    }

    /** What leaves for Ollama: the model, the two messages exactly as built, the JSON schema, the options. */
    @Test
    void theRequestIsThePromptAndTheSchemaAndNothingElse() {
        stub.then(OllamaStub.text(ANSWER));

        client.categorize(prompt);

        JsonNode body = stub.chatBodies().getFirst();
        assertThat(OllamaStub.fields(body)).isSubsetOf("model", "messages", "stream", "format", "options", "tools");
        assertThat(body.path("tools").isEmpty()).as("no tool definitions for categorization").isTrue();
        assertThat(body.get("model").stringValue()).isEqualTo(MODEL);
        assertThat(body.get("stream").asBoolean()).isFalse();
        assertThat(body.get("messages")).hasSize(2);
        assertThat(body.get("messages").get(0).get("role").stringValue()).isEqualTo("system");
        assertThat(body.get("messages").get(0).get("content").stringValue()).isEqualTo(prompt.system());
        assertThat(body.get("messages").get(1).get("role").stringValue()).isEqualTo("user");
        assertThat(body.get("messages").get(1).get("content").stringValue()).isEqualTo(prompt.user());
        // Ollama's structured output: the response's JSON schema
        JsonNode format = body.get("format");
        assertThat(format.isObject()).isTrue();
        assertThat(format.get("type").stringValue()).isEqualTo("object");
        assertThat(format.get("properties").has("answers")).isTrue();
        assertThat(body.get("options").get("temperature").asDouble()).isZero();
        assertThat(body.get("options").get("num_predict").asInt()).isEqualTo(2000);
        // only the redacted merchant text and the amounts of the items (the user message is the built one)
        assertThat(prompt.user()).contains("BRAMBLEWICK NOODLE HOUSE", "-18.40", "QUILLFEATHER MARKET", "-64.02");
    }

    @Test
    void anInvalidAnswerIsNotTrusted() {
        stub.then(OllamaStub.text("Dining for the first, groceries for the second."));

        Result result = client.categorize(prompt);

        assertThat(result.parsed()).isFalse();
        assertThat(stub.requests("/api/chat")).hasSize(1);
    }
}
