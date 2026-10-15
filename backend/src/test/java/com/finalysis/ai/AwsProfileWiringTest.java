package com.finalysis.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.categorize.CategorizationModelClient;
import com.finalysis.categorize.CategorizationPromptBuilder;
import com.finalysis.categorize.CategorizationPromptBuilder.Candidate;
import com.finalysis.categorize.CategorizationPromptBuilder.CategorizationPrompt;
import com.finalysis.categorize.RedactionContext;
import com.finalysis.support.IntegrationTest;
import com.finalysis.support.NoCloudAiGuard;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * The {@code aws} profile wires the cloud models without making a request (docs/plans/phase-4.md §10). The
 * suite-wide {@link NoCloudAiGuard} replaces the credentials, so a request would fail while being signed; this test
 * also proves that it does.
 */
@IntegrationTest
@AutoConfigureMockMvc
@ActiveProfiles("aws")
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AwsProfileWiringTest {

    private final ApplicationContext context;
    private final AiProperties properties;
    private final MockMvc mockMvc;
    private final CategorizationPromptBuilder promptBuilder;
    private final CategorizationModelClient client;

    AwsProfileWiringTest(ApplicationContext context, AiProperties properties, MockMvc mockMvc,
                         CategorizationPromptBuilder promptBuilder, CategorizationModelClient client) {
        this.context = context;
        this.properties = properties;
        this.mockMvc = mockMvc;
        this.promptBuilder = promptBuilder;
        this.client = client;
    }

    @Test
    void theProfileCreatesBothModelsAndCloudMode() {
        assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
        assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(1);
        assertThat(properties.mode()).isEqualTo(AiMode.CLOUD);
        assertThat(properties.provider()).isEqualTo("bedrock");
        assertThat(properties.roles().categorization()).isEqualTo("us.anthropic.claude-haiku-4-5-20251001-v1:0");
        assertThat(properties.roles().embedding()).isEqualTo("amazon.titan-embed-text-v2:0");
        assertThat(properties.nativeStructuredOutput()).isTrue();
    }

    /** Spring AI's Bedrock chat model uses this client bean; its transport retries are the SDK's standard three. */
    @Test
    void theBedrockClientHasTheStandardRetryStrategy() {
        BedrockRuntimeClient client = context.getBean(BedrockRuntimeClient.class);

        assertThat(client.serviceClientConfiguration().overrideConfiguration().retryStrategy())
                .hasValueSatisfying(strategy -> assertThat(strategy.maxAttempts()).isEqualTo(3));
        assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
                .contains(Duration.ofSeconds(30));
    }

    @Test
    void statusCarriesTheBannerDataAndNothingAboutTheAccount() throws Exception {
        String body = mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ai.mode").value("CLOUD"))
                .andExpect(jsonPath("$.ai.provider").value("bedrock"))
                .andExpect(jsonPath("$.ai.models.categorization").value("us.anthropic.claude-haiku-4-5-20251001-v1:0"))
                .andExpect(jsonPath("$.ai.models.embedding").value("amazon.titan-embed-text-v2:0"))
                .andExpect(jsonPath("$.ai.length()").value(3))
                .andExpect(jsonPath("$.ai.models.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("us-east-1", "profile", "region", "key", "credential");
    }

    /** First, before any test makes a call in this context. */
    @Test
    @Order(1)
    void startingTheContextSentNothing() {
        // Building the clients never asks for credentials; only a request would.
        assertThat(context.getBeanNamesForType(AwsCredentialsProvider.class)).containsExactly("noCloudAiCredentials");
        assertThat(guard().requests()).isZero();
    }

    /** A call does reach the client, and the guard stops it before it is signed; the failure is logged as AI-06 says. */
    @Test
    @Order(2)
    void aCallIsBlockedBeforeItIsSentAndLoggedByClassOnly(CapturedOutput output) {
        CategorizationPrompt prompt = promptBuilder.build(List.of("Dining", "Groceries"),
                List.of(new Candidate("BRAMBLEWICK NOODLE HOUSE", null, new BigDecimal("-18.40"), List.of())),
                RedactionContext.EMPTY);
        int before = guard().requests();

        assertThatThrownBy(() -> client.categorize(prompt)).satisfies(e -> assertThat(
                NestedExceptionUtils.getMostSpecificCause(e)).isInstanceOf(NoCloudAiGuard.CloudCallBlockedException.class));

        assertThat(guard().requests()).isGreaterThan(before);
        assertThat(output).containsPattern("ai call role=categorization model=us\\.anthropic\\.claude-haiku-4-5-20251001-v1:0"
                + " items=1 inputTokens=n/a outputTokens=n/a ms=\\d+ outcome=ERROR error=\\w+");
        assertThat(output).doesNotContain("BRAMBLEWICK", "Tests never call a cloud model");
    }

    private NoCloudAiGuard.RefusingCredentialsProvider guard() {
        return context.getBean(NoCloudAiGuard.RefusingCredentialsProvider.class);
    }
}
