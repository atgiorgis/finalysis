package com.finalysis.analytics;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.support.IntegrationTest;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The analytics and alert query services are read-only (docs/plans/phase-5.md §8), so Phase 6 can hand them to the
 * chatbot as tools (SEC-03). Two of the plan's three guarantees live here:
 * <ol>
 *   <li>every public method of a {@code *Service} in {@code analytics} or {@code alert}, except
 *       {@code AlertDismissalService} (the one writer), is {@code @Transactional(readOnly = true)}, on the method or
 *       its class;</li>
 *   <li>such a transaction is {@code READ ONLY} in Postgres itself, which rejects any write.</li>
 * </ol>
 * The third, that no query service depends on {@code AlertDismissalService}, comes with that class in step 5.
 *
 * <p>Step 1 has no service yet, so the rule may check nothing until step 2's services exist; step 2 adds the
 * assertion that it sees them.
 */
@IntegrationTest
class ReadOnlyQueriesTest {

    static final ArchRule QUERY_SERVICES_ARE_READ_ONLY = methods()
            .that().arePublic()
            .and().areDeclaredInClassesThat().resideInAnyPackage("com.finalysis.analytics..", "com.finalysis.alert..")
            .and().areDeclaredInClassesThat().haveSimpleNameEndingWith("Service")
            .and().areDeclaredInClassesThat().doNotHaveSimpleName("AlertDismissalService")
            .and().areNotStatic()
            .should(beReadOnlyTransactional())
            .allowEmptyShould(true)
            .because("query services become read-only chatbot tools in Phase 6 (SEC-03)");

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;

    ReadOnlyQueriesTest(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Test
    void everyQueryServiceMethodIsReadOnly() {
        JavaClasses app = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finalysis");

        QUERY_SERVICES_ARE_READ_ONLY.check(app);
    }

    /** The rule catches a writable method and a class-level annotation without {@code readOnly}. */
    @Test
    void theRuleFailsOnAWritableServiceMethod() {
        JavaClasses violations = new ClassFileImporter().importClasses(WritableMethodService.class,
                WritableClassService.class);
        JavaClasses fine = new ClassFileImporter().importClasses(ReadOnlyMethodService.class,
                ReadOnlyClassService.class);

        assertThat(QUERY_SERVICES_ARE_READ_ONLY.evaluate(violations).getFailureReport().getDetails()).hasSize(2);
        assertThat(QUERY_SERVICES_ARE_READ_ONLY.evaluate(fine).hasViolation()).isFalse();
    }

    /** Postgres enforces it: a write in a read-only transaction fails, so a slip in a query can't change data. */
    @Test
    void aReadOnlyTransactionRejectsWrites() {
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.executeWithoutResult(status ->
                jdbc.update("UPDATE category SET spending_offset = spending_offset WHERE id = -1")))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("read-only transaction");
        Integer categories = readOnly.execute(status ->
                jdbc.queryForObject("SELECT count(*) FROM category", Integer.class));
        assertThat(categories).as("reads still work").isPositive();
    }

    private static ArchCondition<JavaMethod> beReadOnlyTransactional() {
        return new ArchCondition<>("be @Transactional(readOnly = true), on the method or its class") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                Transactional annotation = method.tryGetAnnotationOfType(Transactional.class)
                        .or(() -> method.getOwner().tryGetAnnotationOfType(Transactional.class))
                        .orElse(null);
                boolean readOnly = annotation != null && annotation.readOnly();
                events.add(new SimpleConditionEvent(method, readOnly,
                        method.getFullName() + (readOnly ? " is" : " is not") + " read-only"));
            }
        };
    }

    // Names end in "Service" so the rule applies; they sit in this package, which the rule covers.

    static class WritableMethodService {
        @Transactional
        public void write() {
        }
    }

    @Transactional
    static class WritableClassService {
        public void write() {
        }
    }

    static class ReadOnlyMethodService {
        @Transactional(readOnly = true)
        public void read() {
        }
    }

    @Transactional(readOnly = true)
    static class ReadOnlyClassService {
        public void read() {
        }
    }
}
