package com.finalysis.analytics;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.categorize.CategoryService;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

/**
 * No AI anywhere in Phase 5 (docs/plans/phase-5.md, scope; GR-01): analytics and alert code may not depend on Spring
 * AI, the AWS SDK, the shared AI wiring, chat or categorization. Every number is SQL's or Java's. Phase 6's chat may
 * call analytics; analytics never calls a model.
 */
class NoAiInAnalyticsTest {

    static final ArchRule NO_AI_IN_ANALYTICS = noClasses()
            .that().resideInAnyPackage("com.finalysis.analytics..", "com.finalysis.alert..")
            .should().dependOnClassesThat(resideInAnyPackage("org.springframework.ai..", "software.amazon.awssdk..",
                    "com.finalysis.ai..", "com.finalysis.chat..", "com.finalysis.categorize..")
                    .as("AI, chat or categorization code"))
            .because("GR-01: analytics computes every figure in SQL and Java, never with a model");

    @Test
    void analyticsAndAlertsDependOnNoAiChatOrCategorization() {
        JavaClasses app = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finalysis");

        // The import must actually see the analytics code, or the rule would pass on nothing.
        assertThat(app.contain(TxnBucketSql.class)).isTrue();

        NO_AI_IN_ANALYTICS.check(app);
    }

    /** The rule catches a dependency on a model or on categorization. */
    @Test
    void theRuleFailsOnAModelOrCategorization() {
        assertThat(NO_AI_IN_ANALYTICS.evaluate(new ClassFileImporter().importClasses(UsesChatModel.class))
                .hasViolation()).isTrue();
        assertThat(NO_AI_IN_ANALYTICS.evaluate(new ClassFileImporter().importClasses(UsesCategoryService.class))
                .hasViolation()).isTrue();
    }

    @SuppressWarnings("unused")
    static class UsesChatModel {
        private ChatModel model;
    }

    @SuppressWarnings("unused")
    static class UsesCategoryService {
        private CategoryService categories;
    }
}
