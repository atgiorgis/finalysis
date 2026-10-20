package com.finalysis.analytics;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.alert.AlertDismissalService;
import com.finalysis.alert.AlertQueryService;
import com.finalysis.support.IntegrationTest;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
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
 * The third: nothing in {@code analytics} or {@code alert} depends on {@code AlertDismissalService} except the alert
 * controller, which maps the dismissal endpoints onto it. Phase 6's chat tools will adapt the query services only.
 *
 * <p>The rule allows an empty selection (step 1 had no service), so a test also checks that it sees the services that
 * exist, and their public methods.
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

    static final ArchRule ONLY_THE_CONTROLLER_USES_THE_WRITER = noClasses()
            .that().resideInAnyPackage("com.finalysis.analytics..", "com.finalysis.alert..")
            .and().doNotHaveSimpleName("AlertDismissalService")
            .and().doNotHaveSimpleName("AlertController")
            .should().dependOnClassesThat().haveSimpleName("AlertDismissalService")
            .because("dismissal is the one write, a user action over the API, never a query or a chatbot tool "
                    + "(docs/plans/phase-5.md §8, SEC-03)");

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

        List<String> services = app.stream()
                .filter(c -> c.getPackageName().equals("com.finalysis.analytics"))
                .filter(c -> c.getSimpleName().endsWith("Service"))
                .map(JavaClass::getSimpleName).sorted().toList();
        assertThat(services).as("the rule must see the query services, or it checks nothing")
                .contains("AccountTotalsService", "CategoryTotalsService", "CoverageService", "DrillDownService",
                        "MerchantTotalsService", "MonthlyTotalsService", "TrendsService");
        assertThat(app.contain(AlertQueryService.class)).isTrue();
        assertThat(QUERY_SERVICES_ARE_READ_ONLY.evaluate(new ClassFileImporter().importClasses(
                AlertQueryService.class)).hasViolation()).isFalse();
        assertThat(QUERY_SERVICES_ARE_READ_ONLY.evaluate(new ClassFileImporter().importClasses(
                AlertDismissalService.class)).hasViolation()).as("the one writer is exempt by name").isFalse();
    }

    /** No query class, analytics or alert, depends on the writer; the rule catches one that does. */
    @Test
    void onlyTheControllerUsesTheDismissalService() {
        JavaClasses app = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finalysis");
        assertThat(app.contain(AlertDismissalService.class)).isTrue();

        ONLY_THE_CONTROLLER_USES_THE_WRITER.check(app);

        assertThat(ONLY_THE_CONTROLLER_USES_THE_WRITER.evaluate(new ClassFileImporter().importClasses(
                QueryUsingTheWriter.class)).hasViolation()).isTrue();
    }

    /** Each query method is read-only and REPEATABLE READ, so one response is one snapshot (§2). */
    @Test
    void theQueryServicesReadOneSnapshot() throws NoSuchMethodException {
        for (Method method : List.of(
                MonthlyTotalsService.class.getMethod("months", Optional.class, Optional.class, Optional.class),
                CategoryTotalsService.class.getMethod("categories", Optional.class, Optional.class, Optional.class,
                        Optional.class, boolean.class),
                AccountTotalsService.class.getMethod("accounts", Optional.class, Optional.class),
                CoverageService.class.getMethod("coverage"),
                MerchantTotalsService.class.getMethod("merchants", Optional.class, Optional.class, Optional.class,
                        Optional.class, int.class),
                TrendsService.class.getMethod("trends", Optional.class, Optional.class, Optional.class),
                DrillDownService.class.getMethod("transactions", DrillDownFilter.class),
                CoverageService.class.getMethod("latestMonth"),
                CoverageService.class.getMethod("householdFlags", MonthRange.class),
                AlertQueryService.class.getMethod("alerts", Optional.class, Optional.class, Optional.class,
                        Optional.class, boolean.class, int.class),
                AlertQueryService.class.getMethod("alert", String.class))) {
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional).as(method.getName()).isNotNull();
            assertThat(transactional.readOnly()).as(method.getName()).isTrue();
            assertThat(transactional.isolation()).as(method.getName()).isEqualTo(Isolation.REPEATABLE_READ);
        }
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

    @SuppressWarnings("unused")
    static class QueryUsingTheWriter {
        private AlertDismissalService writer;
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
