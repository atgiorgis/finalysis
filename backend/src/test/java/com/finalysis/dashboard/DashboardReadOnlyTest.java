package com.finalysis.dashboard;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.alert.AlertDismissalService;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dashboard only reads (docs/plans/phase-7.md §3.1): it depends on the analytics and alert query services, never
 * on a feature that writes, the alert dismissal, chat or a model, and runs no SQL of its own; nothing depends on it.
 * Each rule is shown to catch a violation.
 */
class DashboardReadOnlyTest {

    static final String DASHBOARD = "com.finalysis.dashboard..";

    static final ArchRule NO_WRITER_NO_AI = noClasses()
            .that().resideInAPackage(DASHBOARD)
            .should().dependOnClassesThat().resideInAnyPackage("com.finalysis.categorize..",
                    "com.finalysis.ingestion..", "com.finalysis.review..", "com.finalysis.transfer..",
                    "com.finalysis.transaction..", "com.finalysis.account..", "com.finalysis.chat..",
                    "com.finalysis.ai..", "org.springframework.ai..", "org.springframework.jdbc..")
            .orShould().dependOnClassesThat().belongToAnyOf(AlertDismissalService.class)
            .because("the dashboard bundles the read-only query services; corrections, dismissals and imports are "
                    + "user actions over their own endpoints");

    static final ArchRule NOTHING_DEPENDS_ON_THE_DASHBOARD = noClasses()
            .that().resideOutsideOfPackage(DASHBOARD)
            .should().dependOnClassesThat().resideInAPackage(DASHBOARD)
            .because("the dashboard is a leaf that serves the UI");

    private static JavaClasses app;

    @BeforeAll
    static void importApp() {
        app = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finalysis");
        assertThat(app.contain(DashboardService.class)).isTrue();
    }

    @Test
    void theDashboardUsesNoWriterAndNothingDependsOnIt() {
        NO_WRITER_NO_AI.check(app);
        NOTHING_DEPENDS_ON_THE_DASHBOARD.check(app);
    }

    @Test
    void theRulesCatchViolations() {
        JavaClasses violators = new ClassFileImporter().importClasses(Dismisses.class, WritesSql.class);
        assertThat(NO_WRITER_NO_AI.evaluate(violators).getFailureReport().getDetails())
                .anyMatch(line -> line.contains("AlertDismissalService"))
                .anyMatch(line -> line.contains("JdbcTemplate"));
    }

    /** Every public read runs in one read-only, repeatable-read transaction: one snapshot for every figure. */
    @Test
    void everyReadIsOneReadOnlySnapshot() {
        int reads = 0;
        for (Method method : DashboardService.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())) {
                Transactional tx = method.getAnnotation(Transactional.class);
                assertThat(tx).as(method.getName()).isNotNull();
                assertThat(tx.readOnly()).as(method.getName()).isTrue();
                assertThat(tx.isolation()).as(method.getName()).isEqualTo(Isolation.REPEATABLE_READ);
                reads++;
            }
        }
        assertThat(reads).isPositive();
    }

    /** Fixture: a dashboard class that dismisses an alert. */
    static class Dismisses {
        void dismiss(AlertDismissalService dismissals) {
            dismissals.hashCode();
        }
    }

    /** Fixture: a dashboard class with SQL of its own. */
    static class WritesSql {
        void write(JdbcTemplate jdbc) {
            jdbc.update("DELETE FROM alert_dismissal");
        }
    }
}
