package com.finalysis;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.ai.AiProperties;
import com.finalysis.ai.bedrock.BedrockClientConfiguration;
import com.finalysis.categorize.CategorizationModelClient;
import com.finalysis.review.ReviewQueueService;
import com.finalysis.transaction.TransactionController;
import com.finalysis.transfer.TransferDetector;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The feature dependencies of docs/plans/phase-3c.md §6: {@code transfer} uses {@code ingestion} and
 * {@code account}; {@code review} uses {@code ingestion} and {@code transfer}; {@code ingestion} uses
 * neither. Transfer detection is bank-agnostic and AI-free, so {@code transfer} also stays clear of
 * categorization, chat and any model client (no AI anywhere in 3c). Phase 4's {@code transaction} package (the
 * transaction API) is a leaf: it reads txns and calls {@code categorize} for corrections, and nothing depends on it,
 * so the category columns keep the categorization code as their one writer (phase-4.md, step 2 notes). No code names
 * a model provider (phase-4.md §7).
 */
class PackageDependencyTest {

    static final String BEDROCK_CONFIG = "com.finalysis.ai.bedrock..";

    static final ArchRule INGESTION_KNOWS_NO_TRANSFERS_OR_REVIEW = noClasses()
            .that().resideInAPackage("com.finalysis.ingestion..")
            .should().dependOnClassesThat().resideInAnyPackage("com.finalysis.transfer..", "com.finalysis.review..")
            .because("ingestion publishes events; the transfer and review features react to them");

    static final ArchRule TRANSFER_KNOWS_NO_REVIEW = noClasses()
            .that().resideInAPackage("com.finalysis.transfer..")
            .should().dependOnClassesThat().resideInAPackage("com.finalysis.review..")
            .because("the review queue reads transfers, not the other way round");

    static final ArchRule NO_AI_IN_TRANSFERS_OR_REVIEW = noClasses()
            .that().resideInAnyPackage("com.finalysis.transfer..", "com.finalysis.review..")
            .should().dependOnClassesThat().resideInAnyPackage("com.finalysis.categorize..", "com.finalysis.chat..",
                    "com.finalysis.ai..", "org.springframework.ai..", "software.amazon.awssdk..")
            .because("transfer detection is bank-agnostic and uses no AI (phase-3c.md §2.6)");

    static final ArchRule NOTHING_DEPENDS_ON_THE_TRANSACTION_API = noClasses()
            .that().resideOutsideOfPackage("com.finalysis.transaction..")
            .should().dependOnClassesThat().resideInAPackage("com.finalysis.transaction..")
            .because("the transaction API is a leaf; categories are written by the categorization code only");

    /**
     * AI-03 and the local profile to come: code uses Spring AI's {@code ChatModel} and {@code EmbeddingModel} only, so
     * a provider is configuration (the pom and {@code application-<profile>.yml}), never code (phase-4.md §7). The one
     * exception is {@code com.finalysis.ai.bedrock}, the profile-only configuration that sets the Bedrock client's
     * retry strategy (step 7), which nothing may depend on.
     */
    static final ArchRule NO_PROVIDER_IN_CODE = noClasses()
            .that().resideInAPackage("com.finalysis..")
            .and().resideOutsideOfPackage(BEDROCK_CONFIG)
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai.bedrock..",
                    "org.springframework.ai.model.bedrock..", "org.springframework.ai.ollama..",
                    "org.springframework.ai.model.ollama..", "software.amazon..")
            .because("a model provider is chosen by profile; code depends only on Spring AI's interfaces");

    static final ArchRule NOTHING_DEPENDS_ON_PROVIDER_CONFIG = noClasses()
            .that().resideOutsideOfPackage(BEDROCK_CONFIG)
            .should().dependOnClassesThat().resideInAPackage(BEDROCK_CONFIG)
            .because("the provider's client configuration is picked up by profile, never called");

    private static JavaClasses app;

    @BeforeAll
    static void importApp() {
        app = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finalysis");
        // The import must see the code the rules are about, or they would pass on nothing.
        assertThat(app.contain(TransferDetector.class)).isTrue();
        assertThat(app.contain(ReviewQueueService.class)).isTrue();
        assertThat(app.contain(TransactionController.class)).isTrue();
        assertThat(app.contain(CategorizationModelClient.class)).isTrue();
        assertThat(app.contain(AiProperties.class)).isTrue();
        assertThat(app.contain(BedrockClientConfiguration.class)).isTrue();
    }

    @Test
    void ingestionDependsOnNeitherTransferNorReview() {
        INGESTION_KNOWS_NO_TRANSFERS_OR_REVIEW.check(app);
    }

    @Test
    void transferDoesNotDependOnReview() {
        TRANSFER_KNOWS_NO_REVIEW.check(app);
    }

    @Test
    void transferAndReviewUseNoAiCategorizationOrChat() {
        NO_AI_IN_TRANSFERS_OR_REVIEW.check(app);
    }

    @Test
    void nothingDependsOnTheTransactionApi() {
        NOTHING_DEPENDS_ON_THE_TRANSACTION_API.check(app);
    }

    @Test
    void noCodeDependsOnAModelProvider() {
        NO_PROVIDER_IN_CODE.check(app);
        NOTHING_DEPENDS_ON_PROVIDER_CONFIG.check(app);
    }

    /** The rules catch a violation. */
    @Test
    void theRulesFailOnAViolation() {
        JavaClasses violation = new ClassFileImporter().importClasses(com.finalysis.ingestion.UsesTransfer.class);

        assertThat(INGESTION_KNOWS_NO_TRANSFERS_OR_REVIEW.evaluate(violation).hasViolation()).isTrue();
    }

    @Test
    void theProviderRuleFailsOnAProviderClass() {
        JavaClasses bedrock = new ClassFileImporter().importClasses(UsesBedrock.class);
        JavaClasses sdk = new ClassFileImporter().importClasses(UsesAwsSdk.class);
        JavaClasses springAi = new ClassFileImporter().importClasses(UsesSpringAiInterfaces.class);

        assertThat(NO_PROVIDER_IN_CODE.evaluate(bedrock).hasViolation()).isTrue();
        assertThat(NO_PROVIDER_IN_CODE.evaluate(sdk).hasViolation()).isTrue();
        assertThat(NO_PROVIDER_IN_CODE.evaluate(springAi).hasViolation()).isFalse();
    }

    @SuppressWarnings("unused")
    static class UsesBedrock {
        private org.springframework.ai.bedrock.converse.BedrockChatOptions options;
    }

    @SuppressWarnings("unused")
    static class UsesAwsSdk {
        private software.amazon.awssdk.regions.Region region;
    }

    @SuppressWarnings("unused")
    static class UsesSpringAiInterfaces {
        private org.springframework.ai.chat.model.ChatModel chat;
        private org.springframework.ai.embedding.EmbeddingModel embedding;
    }
}
