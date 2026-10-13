package com.finalysis.ingestion;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.categorize.Category;
import com.finalysis.categorize.CategoryService;
import com.finalysis.categorize.CategorySource;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * SEC-09: statement text never reaches a model. Ingestion code (parsers, PDF extraction, import,
 * reconciliation) may not depend on Spring AI, the AWS SDK (Bedrock), the chat package, or
 * categorization, which is where AI calls will live from Phase 4.
 *
 * <p>The one exception is the categorization data model that {@link Txn} maps: the {@link Category}
 * entity and the {@link CategorySource} enum. They are table columns, not behaviour.
 *
 * <p>ING-14's later local-model extraction (Phase 8) needs an explicit, reviewed exception here.
 */
class NoAiInIngestionTest {

    private static final DescribedPredicate<JavaClass> AI_OR_CATEGORIZATION = resideInAnyPackage(
            "org.springframework.ai..", "software.amazon.awssdk..", "com.finalysis.chat..")
            .or(resideInAPackage("com.finalysis.categorize..").and(not(belongToAnyOf(Category.class, CategorySource.class))))
            .as("AI, chat or categorization code (other than the Category entity and CategorySource)");

    static final ArchRule NO_AI_IN_INGESTION = noClasses()
            .that().resideInAPackage("com.finalysis.ingestion..")
            .should().dependOnClassesThat(AI_OR_CATEGORIZATION)
            .because("SEC-09: statement text and PDF text must never reach a model");

    @Test
    void ingestionDoesNotDependOnAiChatOrCategorization() {
        JavaClasses app = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finalysis");

        // The import must actually see the ingestion code, or the rule would pass on nothing.
        assertThat(app.contain(StatementImportService.class)).isTrue();
        assertThat(app.contain(com.finalysis.ingestion.pdf.PdfTextExtractor.class)).isTrue();

        NO_AI_IN_INGESTION.check(app);
    }

    /** The rule catches a dependency on categorization behaviour... */
    @Test
    void theRuleFailsOnCategorizationBehaviour() {
        JavaClasses violation = new ClassFileImporter().importClasses(UsesCategoryService.class);

        assertThat(NO_AI_IN_INGESTION.evaluate(violation).hasViolation()).isTrue();
    }

    /** ...and allows the data model that Txn maps. */
    @Test
    void theRuleAllowsTheCategoryEntityAndSource() {
        JavaClasses allowed = new ClassFileImporter().importClasses(UsesCategoryEntity.class);

        assertThat(NO_AI_IN_INGESTION.evaluate(allowed).hasViolation()).isFalse();
    }

    @SuppressWarnings("unused")
    static class UsesCategoryService {
        private CategoryService categories;
    }

    @SuppressWarnings("unused")
    static class UsesCategoryEntity {
        private Category category;
        private CategorySource source;
    }
}
