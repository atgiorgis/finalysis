package com.finalysis;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.ai.AiProperties;
import com.finalysis.alert.AlertQueryService;
import com.finalysis.analytics.Arithmetic;
import com.finalysis.analytics.CoverageService;
import com.finalysis.analytics.CoveredDays;
import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.MonthRanges;
import com.finalysis.analytics.MonthlySeries;
import com.finalysis.analytics.RecurringChargesService;
import com.finalysis.analytics.RecurringSeriesQuery;
import com.finalysis.analytics.RecurringStatusFilter;
import com.finalysis.analytics.ReferenceQuery;
import com.finalysis.analytics.TrendsService;
import com.finalysis.analytics.TxnBucketSql;
import com.finalysis.analytics.UsesAlert;
import com.finalysis.ai.bedrock.BedrockClientConfiguration;
import com.finalysis.ai.outbound.Redactor;
import com.finalysis.categorize.CategorizationModelClient;
import com.finalysis.review.ReviewQueueService;
import com.finalysis.transaction.TransactionController;
import com.finalysis.transfer.TransferDetector;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.time.YearMonth;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The feature dependencies of docs/plans/phase-3c.md §6: {@code transfer} uses {@code ingestion} and
 * {@code account}; {@code review} uses {@code ingestion} and {@code transfer}; {@code ingestion} uses
 * neither. Transfer detection is bank-agnostic and AI-free, so {@code transfer} also stays clear of
 * categorization, chat and any model client (no AI anywhere in 3c). Phase 4's {@code transaction} package (the
 * transaction API) is a leaf: it reads txns and calls {@code categorize} for corrections, and nothing depends on it,
 * so the category columns keep the categorization code as their one writer (phase-4.md, step 2 notes). No code names
 * a model provider (phase-4.md §7). Phase 5's {@code analytics} and {@code alert} read the other features' tables in
 * SQL and depend on none of their classes, and {@code analytics} knows nothing of {@code alert} (phase-5.md §3.2).
 * The analytics classes made public only so the alerts can use them (step 5, and the recurring series of step 6) are
 * used by {@code alert} alone; everything else, Phase 6's chat tools included, goes through the query services.
 */
class PackageDependencyTest {

    static final String BEDROCK_CONFIG = "com.finalysis.ai.bedrock..";
    static final String OUTBOUND = "com.finalysis.ai.outbound..";

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

    static final ArchRule ANALYTICS_READS_TABLES_NOT_FEATURES = noClasses()
            .that().resideInAnyPackage("com.finalysis.analytics..", "com.finalysis.alert..")
            .should().dependOnClassesThat().resideInAnyPackage("com.finalysis.categorize..",
                    "com.finalysis.ingestion..", "com.finalysis.transfer..", "com.finalysis.review..",
                    "com.finalysis.transaction..")
            .because("analytics reads those features' tables in SQL, so their code can change without it");

    /**
     * The redaction and guard code (phase-6.md §18 decision 5) serves categorization and chat, so it depends on neither,
     * on no other feature, and on no model: it decides what may leave before any model is involved.
     */
    static final ArchRule OUTBOUND_KNOWS_NO_FEATURE = noClasses()
            .that().resideInAPackage(OUTBOUND)
            .should().dependOnClassesThat().resideInAnyPackage("com.finalysis.categorize..", "com.finalysis.chat..",
                    "com.finalysis.ingestion..", "com.finalysis.transfer..", "com.finalysis.review..",
                    "com.finalysis.transaction..", "com.finalysis.account..", "com.finalysis.analytics..",
                    "com.finalysis.alert..", "org.springframework.ai..")
            .because("categorization and chat share it; it must not know either of them");

    static final ArchRule ANALYTICS_KNOWS_NO_ALERTS = noClasses()
            .that().resideInAPackage("com.finalysis.analytics..")
            .should().dependOnClassesThat().resideInAPackage("com.finalysis.alert..")
            .because("alerts are built on analytics figures, not the other way round");

    /**
     * Service methods public for the alerts, which validate no range or bound of their own: three of
     * {@code CoverageService} (its {@code coverage()} is the endpoint's) and the range overload of {@code TrendsService}
     * (the category spike check's one bucket query; {@code trends(month, accountId, categoryId)} is the endpoint's).
     */
    static final Set<String> ALERT_ONLY_METHODS = Set.of(
            "com.finalysis.analytics.CoverageService.latestMonth()",
            "com.finalysis.analytics.CoverageService.householdFlags(com.finalysis.analytics.MonthRange)",
            "com.finalysis.analytics.CoverageService.coveredDays(java.time.LocalDate)",
            "com.finalysis.analytics.TrendsService.trends(com.finalysis.analytics.MonthRange)");

    /**
     * Public in {@code analytics} only for {@code alert}: the helpers made public in step 5 ({@code Arithmetic},
     * {@code MonthRanges}, {@code ReferenceQuery}, three {@code CoverageService} methods), those of step 6
     * ({@code MonthlySeries}, {@code RecurringSeriesQuery}, {@code CoveredDays}, {@code TrendsService}'s range
     * overload) and the bucket SQL. They are building
     * blocks, without the services' validation, bounds and read-only transactions.
     */
    static final ArchRule ANALYTICS_INTERNALS_ONLY_FOR_ALERTS = noClasses()
            .that().resideOutsideOfPackages("com.finalysis.analytics..", "com.finalysis.alert..")
            .should().dependOnClassesThat().belongToAnyOf(Arithmetic.class, MonthRanges.class, ReferenceQuery.class,
                    TxnBucketSql.class, MonthlySeries.class, RecurringSeriesQuery.class, CoveredDays.class)
            .orShould().accessTargetWhere(new DescribedPredicate<JavaAccess<?>>(
                    "a service method public for the alerts " + ALERT_ONLY_METHODS) {
                @Override
                public boolean test(JavaAccess<?> access) {
                    return ALERT_ONLY_METHODS.contains(access.getTarget().getFullName());
                }
            })
            .because("they are public for the alerts only; other code (Phase 6's chat tools included) uses the "
                    + "read-only, bounded query services");

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
        assertThat(app.contain(TxnBucketSql.class)).isTrue();
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

    @Test
    void analyticsAndAlertsUseNoOtherFeaturesCode() {
        ANALYTICS_READS_TABLES_NOT_FEATURES.check(app);
    }

    /** Alerts may depend on analytics, never the reverse; analytics' alert-only classes are used by alerts alone. */
    @Test
    void alertsDependOnAnalyticsNeverTheReverse() {
        assertThat(app.contain(AlertQueryService.class)).isTrue();
        assertThat(app.contain(MonthlySeries.class)).isTrue();

        ANALYTICS_KNOWS_NO_ALERTS.check(app);
        ANALYTICS_INTERNALS_ONLY_FOR_ALERTS.check(app);
    }

    /** Both rules catch a violation: analytics using an alert class; other code using an alert-only helper. */
    @Test
    void theAnalyticsRulesFailOnAViolation() {
        JavaClasses analyticsUsesAlert = new ClassFileImporter().importClasses(UsesAlert.class);
        JavaClasses helper = new ClassFileImporter().importClasses(UsesAlertOnlyHelper.class);
        JavaClasses coverageCall = new ClassFileImporter().importClasses(CallsAlertOnlyCoverage.class);
        JavaClasses trendsCall = new ClassFileImporter().importClasses(CallsTheTrendsRangeOverload.class);
        JavaClasses service = new ClassFileImporter().importClasses(UsesTheQueryService.class);

        assertThat(ANALYTICS_KNOWS_NO_ALERTS.evaluate(analyticsUsesAlert).hasViolation()).isTrue();
        assertThat(ANALYTICS_INTERNALS_ONLY_FOR_ALERTS.evaluate(helper).hasViolation()).isTrue();
        assertThat(ANALYTICS_INTERNALS_ONLY_FOR_ALERTS.evaluate(coverageCall).hasViolation()).isTrue();
        assertThat(ANALYTICS_INTERNALS_ONLY_FOR_ALERTS.evaluate(trendsCall).hasViolation()).isTrue();
        assertThat(ANALYTICS_INTERNALS_ONLY_FOR_ALERTS.evaluate(service).hasViolation())
                .as("the services are the public contract").isFalse();
    }

    /** The redaction and guard code depends on no feature; categorization uses it, as chat will. */
    @Test
    void outboundCodeKnowsNoFeature() {
        assertThat(app.contain(Redactor.class)).isTrue();
        OUTBOUND_KNOWS_NO_FEATURE.check(app);

        JavaClasses violation = new ClassFileImporter().importClasses(com.finalysis.ai.outbound.UsesCategorize.class);
        assertThat(OUTBOUND_KNOWS_NO_FEATURE.evaluate(violation).hasViolation()).isTrue();
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
    static class UsesAlertOnlyHelper {
        private MonthlySeries series;
    }

    static class CallsAlertOnlyCoverage {
        Object latest(CoverageService coverage) {
            return coverage.latestMonth();
        }
    }

    static class CallsTheTrendsRangeOverload {
        Object trends(TrendsService trends) {
            return trends.trends(new MonthRange(YearMonth.of(2026, 1), YearMonth.of(2026, 3)));
        }
    }

    static class UsesTheQueryService {
        Object recurring(RecurringChargesService recurring, CoverageService coverage, TrendsService trends) {
            coverage.coverage();
            trends.trends(Optional.empty(), Optional.empty(), Optional.empty());
            return recurring.recurring(Optional.empty(), RecurringStatusFilter.ALL, 10);
        }
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
