package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ai.ProviderSet;
import com.finalysis.ai.AiPath;
import com.finalysis.ai.ActiveProviders;
import com.finalysis.ai.AiProperties;
import com.finalysis.ai.outbound.OutboundPayloadGuard;
import com.finalysis.ai.outbound.RedactionContext;
import com.finalysis.ai.outbound.Redactor;
import com.finalysis.categorize.CategorizationModelClient.Result;
import com.finalysis.categorize.CategorizationPromptBuilder.Candidate;
import com.finalysis.categorize.CategorizationPromptBuilder.CategorizationPrompt;
import com.finalysis.categorize.CategorizationResponse.Answer;
import com.finalysis.support.FakeChatModel;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * One categorization call through Spring AI's {@link ChatModel} (docs/plans/phase-4.md §4.3, AI-06), against a fake
 * model: what is sent, the options, the structured answer, and the token log line.
 */
@ExtendWith(OutputCaptureExtension.class)
class CategorizationModelClientTest {

    private static final String MODEL = "categorization-model";
    private static final String ANSWER = """
            {"answers": [{"key": "i1", "category": "Dining", "confidence": 0.92},
                         {"key": "i2", "category": "Groceries", "confidence": 0.3}]}""";

    private final CategorizationPrompt prompt = new CategorizationPromptBuilder(
            new Redactor(MerchantCleaningRules.loadDefault()), new OutboundPayloadGuard())
            .build(List.of("Dining", "Groceries"), List.of(
                    new Candidate("BRAMBLEWICK NOODLE HOUSE", null, new BigDecimal("-18.40"), List.of()),
                    new Candidate("QUILLFEATHER MARKET", null, new BigDecimal("-64.02"), List.of())),
                    RedactionContext.EMPTY);

    @Test
    void sendsExactlyThePromptsTwoMessagesWithTheRoleOptions(CapturedOutput output) {
        FakeChatModel model = new FakeChatModel().answering(ANSWER, 812, 64);

        Result result = client(model, cloud(true)).categorize(prompt);

        assertThat(model.prompts()).hasSize(1);
        Prompt sent = model.prompts().getFirst();
        assertThat(sent.getInstructions()).extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER);
        assertThat(sent.getInstructions().get(0).getText()).isEqualTo(prompt.system());
        // Native structured output: the schema goes in the options, the user message is exactly the built one.
        assertThat(sent.getInstructions().get(1).getText()).isEqualTo(prompt.user());
        assertThat(sent.getOptions().getModel()).isEqualTo(MODEL);
        assertThat(sent.getOptions().getTemperature()).isEqualTo(0.0);
        assertThat(sent.getOptions().getMaxTokens()).isEqualTo(2000);
        assertThat(sent.getOptions()).isInstanceOfSatisfying(StructuredOutputChatOptions.class,
                options -> assertThat(options.getOutputSchema()).contains("\"answers\"", "\"confidence\"", "\"number\""));

        assertThat(result.parsed()).isTrue();
        assertThat(result.response().answers()).containsExactly(
                new Answer("i1", "Dining", 0.92), new Answer("i2", "Groceries", 0.3));
        assertThat(result.inputTokens()).isEqualTo(812);
        assertThat(result.outputTokens()).isEqualTo(64);
        assertThat(result.usageReported()).isTrue();
        assertThat(output).containsPattern(
                "ai call role=categorization model=categorization-model items=2 inputTokens=812 outputTokens=64 ms=\\d+ outcome=OK");
    }

    @Test
    void withoutNativeOutputTheFormatInstructionsFollowTheData() {
        FakeChatModel model = FakeChatModel.withoutNativeStructuredOutput().answering(ANSWER, 900, 70);

        Result result = client(model, cloud(false)).categorize(prompt);

        String user = model.prompts().getFirst().getInstructions().get(1).getText();
        assertThat(user).startsWith(prompt.user()).contains("JSON Schema").hasSizeGreaterThan(prompt.user().length());
        assertThat(result.parsed()).isTrue();
    }

    @Test
    void outputThatDoesNotParseIsNullAndStillLogsItsTokens(CapturedOutput output) {
        FakeChatModel model = new FakeChatModel().answering("Sure! BRAMBLEWICK NOODLE HOUSE is Dining.", 800, 12);

        Result result = client(model, cloud(true)).categorize(prompt);

        assertThat(result.parsed()).isFalse();
        assertThat(result.inputTokens()).isEqualTo(800);
        assertThat(output).containsPattern("items=2 inputTokens=800 outputTokens=12 ms=\\d+ outcome=INVALID_OUTPUT"
                + " error=\\w+");
        assertThat(output).doesNotContain("BRAMBLEWICK", "Sure!");
    }

    /** Spring AI stands in zeros for usage a provider didn't give; the log says n/a, and the result has no counts. */
    @Test
    void unreportedUsageIsLoggedAsNotAvailableNotZero(CapturedOutput output) {
        Result result = client(new FakeChatModel().answering(ANSWER, null, null), cloud(true)).categorize(prompt);

        assertThat(result.usageReported()).isFalse();
        assertThat(result.inputTokens()).isNull();
        assertThat(result.outputTokens()).isNull();
        assertThat(output).containsPattern("items=2 inputTokens=n/a outputTokens=n/a ms=\\d+ outcome=OK");
        assertThat(output).doesNotContain("inputTokens=0");
    }

    @Test
    void usageIsReportedOnlyWithRealCounts() {
        assertThat(CategorizationModelClient.reported(null)).isFalse();
        assertThat(CategorizationModelClient.reported(new EmptyUsage())).isFalse();
        assertThat(CategorizationModelClient.reported(new DefaultUsage(null, null))).isFalse();
        assertThat(CategorizationModelClient.reported(new DefaultUsage(0, 0))).isFalse();
        assertThat(CategorizationModelClient.reported(new DefaultUsage(812, 0))).isTrue();
        assertThat(CategorizationModelClient.reported(new DefaultUsage(812, 64))).isTrue();
    }

    @Test
    void aFailureIsLoggedByClassOnlyAndRethrown(CapturedOutput output) {
        FakeChatModel model = new FakeChatModel().failingWith(
                new IllegalStateException("Throttled while reading BRAMBLEWICK NOODLE HOUSE -18.40"));

        assertThatThrownBy(() -> client(model, cloud(true)).categorize(prompt))
                .isInstanceOf(IllegalStateException.class);

        assertThat(output).containsPattern(
                "ai call role=categorization model=categorization-model items=2 inputTokens=n/a outputTokens=n/a ms=\\d+"
                        + " outcome=ERROR error=IllegalStateException");
        assertThat(output).doesNotContain("Throttled", "BRAMBLEWICK", "18.40");
    }

    @Test
    void noCallWhenAiIsOffOrThereIsNothingToSend() {
        FakeChatModel model = new FakeChatModel().answering(ANSWER, 1, 1);
        AiProperties off = new AiProperties(false, AiPath.OFF, null, new AiProperties.Roles(null, null, null, null),
                true);
        CategorizationPrompt empty = new CategorizationPromptBuilder(
                new Redactor(MerchantCleaningRules.loadDefault()), new OutboundPayloadGuard())
                .build(List.of("Dining"), List.of(), RedactionContext.EMPTY);

        assertThatThrownBy(() -> client(model, off).categorize(prompt)).hasMessage("AI is off");
        assertThatThrownBy(() -> client(null, cloud(true)).categorize(prompt)).hasMessage("AI is off");
        assertThatThrownBy(() -> client(model, cloud(true)).categorize(empty)).hasMessage("Nothing to send");
        assertThat(model.prompts()).isEmpty();
    }

    /** Cloud over this model when Cloud is available, else OFF; without a model, providers that couldn't be built. */
    private static CategorizationModelClient client(ChatModel model, AiProperties properties) {
        return new CategorizationModelClient(ActiveProviders.fixed(properties.cloudAvailable() ? AiPath.CLOUD : AiPath.OFF,
                model == null ? null : ProviderSet.cloud(properties, 1024, model, null, null)));
    }

    private static AiProperties cloud(boolean nativeStructuredOutput) {
        return new AiProperties(true, AiPath.CLOUD, "test",
                new AiProperties.Roles("main-model", MODEL, "embedding-model", ""), nativeStructuredOutput);
    }
}
