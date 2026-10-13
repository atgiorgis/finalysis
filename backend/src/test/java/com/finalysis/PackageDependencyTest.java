package com.finalysis;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.review.ReviewQueueService;
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
 * categorization, chat and any model client (no AI anywhere in 3c).
 */
class PackageDependencyTest {

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
                    "org.springframework.ai..", "software.amazon.awssdk..")
            .because("transfer detection is bank-agnostic and uses no AI (phase-3c.md §2.6)");

    private static JavaClasses app;

    @BeforeAll
    static void importApp() {
        app = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finalysis");
        // The import must see the code the rules are about, or they would pass on nothing.
        assertThat(app.contain(TransferDetector.class)).isTrue();
        assertThat(app.contain(ReviewQueueService.class)).isTrue();
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

    /** The rules catch a violation. */
    @Test
    void theRulesFailOnAViolation() {
        JavaClasses violation = new ClassFileImporter().importClasses(com.finalysis.ingestion.UsesTransfer.class);

        assertThat(INGESTION_KNOWS_NO_TRANSFERS_OR_REVIEW.evaluate(violation).hasViolation()).isTrue();
    }
}
