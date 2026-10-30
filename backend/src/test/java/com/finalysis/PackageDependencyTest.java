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
import com.finalysis.vault.VaultFiles;
import com.finalysis.vault.VaultLocking;
import com.finalysis.vault.VaultService;
import com.finalysis.vault.VaultUnlocked;
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
    static final String OLLAMA_CLIENTS = "com.finalysis.ai.ollama..";
    static final String[] BEDROCK_CLASSES = {"org.springframework.ai.bedrock..", "org.springframework.ai.model.bedrock..",
            "software.amazon.."};
    static final String[] OLLAMA_CLASSES = {"org.springframework.ai.ollama..", "org.springframework.ai.model.ollama.."};
    static final String OUTBOUND = "com.finalysis.ai.outbound..";
    static final String SELFHOST = "com.finalysis.ai.selfhost..";
    static final String EC2_CLASSES = "software.amazon.awssdk.services.ec2..";

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
     * AI-03 (phase-4.md §7, phase-9.md §3.2): code uses Spring AI's {@code ChatModel} and {@code EmbeddingModel} only,
     * through the path's providers. Two packages are the exceptions, each touching only its own provider's classes:
     * {@code com.finalysis.ai.bedrock}, the profile-only configuration of the Bedrock client (step 7), and
     * {@code com.finalysis.ai.ollama}, which builds the Ollama clients for the Self-hosted and Local paths.
     */
    static final ArchRule NO_PROVIDER_IN_CODE = noClasses()
            .that().resideInAPackage("com.finalysis..")
            .and().resideOutsideOfPackages(BEDROCK_CONFIG, OLLAMA_CLIENTS, SELFHOST)
            .should().dependOnClassesThat().resideInAnyPackage(BEDROCK_CLASSES)
            .orShould().dependOnClassesThat().resideInAnyPackage(OLLAMA_CLASSES)
            .because("code depends only on Spring AI's interfaces; providers live in their own packages");

    static final ArchRule BEDROCK_PACKAGE_USES_NO_OLLAMA = noClasses()
            .that().resideInAPackage(BEDROCK_CONFIG)
            .should().dependOnClassesThat().resideInAnyPackage(OLLAMA_CLASSES)
            .because("each provider package touches only its own provider's classes");

    static final ArchRule OLLAMA_PACKAGE_USES_NO_BEDROCK = noClasses()
            .that().resideInAPackage(OLLAMA_CLIENTS)
            .should().dependOnClassesThat().resideInAnyPackage(BEDROCK_CLASSES)
            .because("each provider package touches only its own provider's classes");

    static final ArchRule NOTHING_DEPENDS_ON_PROVIDER_CONFIG = noClasses()
            .that().resideOutsideOfPackage(BEDROCK_CONFIG)
            .should().dependOnClassesThat().resideInAPackage(BEDROCK_CONFIG)
            .because("the provider's client configuration is picked up by profile, never called");

    /** {@code com.finalysis.ai} reaches the Ollama clients through its own {@code OllamaClients} interface. */
    static final ArchRule NOTHING_DEPENDS_ON_THE_OLLAMA_PACKAGE = noClasses()
            .that().resideOutsideOfPackage(OLLAMA_CLIENTS)
            .should().dependOnClassesThat().resideInAPackage(OLLAMA_CLIENTS)
            .because("the Ollama clients are built behind com.finalysis.ai's OllamaClients interface");

    /**
     * Phase 9 step 9c (docs/plans/phase-9.md §5.4): the AWS SDK's EC2 classes only in {@code com.finalysis.ai.selfhost},
     * which uses no model provider; nothing outside it depends on it (chat reads the cost through {@code SelfHostMeter}).
     */
    static final ArchRule EC2_ONLY_IN_SELFHOST = noClasses()
            .that().resideOutsideOfPackage(SELFHOST)
            .should().dependOnClassesThat().resideInAPackage(EC2_CLASSES)
            .because("only the Self-hosted controls talk to EC2");

    static final ArchRule SELFHOST_USES_NO_MODEL_PROVIDER = noClasses()
            .that().resideInAPackage(SELFHOST)
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai..",
                    "software.amazon.awssdk.services.bedrock..", "software.amazon.awssdk.services.bedrockruntime..",
                    BEDROCK_CONFIG, OLLAMA_CLIENTS)
            .because("the Self-hosted controls start and stop a server; Ollama is reached through OllamaClients");

    static final ArchRule NOTHING_DEPENDS_ON_SELFHOST = noClasses()
            .that().resideOutsideOfPackage(SELFHOST)
            .should().dependOnClassesThat().resideInAPackage(SELFHOST)
            .because("features read the server's cost through com.finalysis.ai's SelfHostMeter");

    /** Phase 9: no feature depends on a provider package, only on {@code com.finalysis.ai}'s interfaces. */
    static final ArchRule NO_FEATURE_DEPENDS_ON_A_PROVIDER_PACKAGE = noClasses()
            .that().resideOutsideOfPackage("com.finalysis.ai..")
            .should().dependOnClassesThat().resideInAnyPackage(BEDROCK_CONFIG, OLLAMA_CLIENTS)
            .because("features read the active path's providers, never a provider's package");

    /**
     * The vault (docs/plans/phase-8.md §5.5) holds the database's lifecycle and depends on no feature; features depend
     * on it only for its two events and, since step 4, {@link VaultFiles} (where kept statement files go, §7).
     * {@code api} reads its status for {@code GET /api/status}.
     */
    static final ArchRule VAULT_KNOWS_NO_FEATURE = noClasses()
            .that().resideInAPackage("com.finalysis.vault..")
            .should().dependOnClassesThat().resideInAnyPackage("com.finalysis.account..", "com.finalysis.ingestion..",
                    "com.finalysis.categorize..", "com.finalysis.chat..", "com.finalysis.analytics..",
                    "com.finalysis.alert..", "com.finalysis.transfer..", "com.finalysis.review..",
                    "com.finalysis.transaction..", "com.finalysis.dashboard..", "com.finalysis.ai..",
                    "com.finalysis.api..", "com.finalysis.access..")
            .because("the vault opens and closes the database for every feature and must not know any of them");

    /**
     * Phone access (docs/plans/phase-8.md §5.5, §9) sits beside the vault: it uses the vault (the password check, the
     * TLS folder, the lock events) and no feature; nothing but its own package uses it.
     */
    static final ArchRule ACCESS_KNOWS_NO_FEATURE = noClasses()
            .that().resideInAPackage("com.finalysis.access..")
            .should().dependOnClassesThat().resideInAnyPackage("com.finalysis.account..", "com.finalysis.ingestion..",
                    "com.finalysis.categorize..", "com.finalysis.chat..", "com.finalysis.analytics..",
                    "com.finalysis.alert..", "com.finalysis.transfer..", "com.finalysis.review..",
                    "com.finalysis.transaction..", "com.finalysis.dashboard..", "com.finalysis.ai..")
            .because("login and the LAN listener guard every feature and must not know any of them");

    static final ArchRule NOTHING_ELSE_USES_ACCESS = noClasses()
            .that().resideOutsideOfPackage("com.finalysis.access..")
            .should().dependOnClassesThat().resideInAPackage("com.finalysis.access..")
            .because("phone access is wired by its own configuration");

    static final ArchRule FEATURES_USE_ONLY_THE_VAULTS_EVENTS = noClasses()
            .that().resideOutsideOfPackages("com.finalysis.vault..", "com.finalysis.api..", "com.finalysis.access..")
            .should().dependOnClassesThat(DescribedPredicate.describe("vault classes other than its events and files",
                    c -> c.getPackageName().startsWith("com.finalysis.vault")
                            && !c.isEquivalentTo(VaultUnlocked.class) && !c.isEquivalentTo(VaultLocking.class)
                            && !c.isEquivalentTo(VaultFiles.class)))
            .because("features react to unlock and lock and keep files where the vault says; only the status "
                    + "endpoint reads the vault itself");

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
        assertThat(app.contain(com.finalysis.ai.selfhost.SelfHostControl.class)).isTrue();
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
        BEDROCK_PACKAGE_USES_NO_OLLAMA.check(app);
        OLLAMA_PACKAGE_USES_NO_BEDROCK.check(app);
        NOTHING_DEPENDS_ON_PROVIDER_CONFIG.check(app);
        NOTHING_DEPENDS_ON_THE_OLLAMA_PACKAGE.check(app);
        NO_FEATURE_DEPENDS_ON_A_PROVIDER_PACKAGE.check(app);
    }

    @Test
    void ec2OnlyInTheSelfHostedControls() {
        EC2_ONLY_IN_SELFHOST.check(app);
        SELFHOST_USES_NO_MODEL_PROVIDER.check(app);
        NOTHING_DEPENDS_ON_SELFHOST.check(app);
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

    @Test
    void theVaultKnowsNoFeatureAndFeaturesKnowOnlyItsEvents() {
        assertThat(app.contain(VaultService.class)).isTrue();
        VAULT_KNOWS_NO_FEATURE.check(app);
        FEATURES_USE_ONLY_THE_VAULTS_EVENTS.check(app);
        assertThat(app.contain(com.finalysis.access.LanListener.class)).isTrue();
        ACCESS_KNOWS_NO_FEATURE.check(app);
        NOTHING_ELSE_USES_ACCESS.check(app);

        JavaClasses vaultViolation = new ClassFileImporter().importClasses(com.finalysis.vault.UsesAccount.class);
        JavaClasses featureViolation = new ClassFileImporter()
                .importClasses(com.finalysis.chat.UsesVaultService.class);
        assertThat(VAULT_KNOWS_NO_FEATURE.evaluate(vaultViolation).hasViolation()).isTrue();
        assertThat(FEATURES_USE_ONLY_THE_VAULTS_EVENTS.evaluate(featureViolation).hasViolation()).isTrue();
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
        JavaClasses ollama = new ClassFileImporter().importClasses(UsesOllama.class);

        assertThat(NO_PROVIDER_IN_CODE.evaluate(ollama).hasViolation()).isTrue();
        assertThat(NO_PROVIDER_IN_CODE.evaluate(bedrock).hasViolation()).isTrue();
        assertThat(NO_PROVIDER_IN_CODE.evaluate(sdk).hasViolation()).isTrue();
        assertThat(NO_PROVIDER_IN_CODE.evaluate(springAi).hasViolation()).isFalse();
    }

    @Test
    void theEc2RuleFailsOnEc2OutsideTheSelfHostedControls() {
        JavaClasses ec2 = new ClassFileImporter().importClasses(UsesEc2.class);

        assertThat(EC2_ONLY_IN_SELFHOST.evaluate(ec2).hasViolation()).isTrue();
        assertThat(NO_PROVIDER_IN_CODE.evaluate(ec2).hasViolation()).isTrue();
    }

    @SuppressWarnings("unused")
    static class UsesEc2 {
        private software.amazon.awssdk.services.ec2.Ec2Client client;
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
    static class UsesOllama {
        private org.springframework.ai.ollama.api.OllamaChatOptions options;
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
