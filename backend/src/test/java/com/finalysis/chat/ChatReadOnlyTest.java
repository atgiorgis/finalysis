package com.finalysis.chat;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.alert.AlertDismissalService;
import com.finalysis.alert.AlertQueryService;
import com.finalysis.chat.citation.CitationQuery;
import com.finalysis.chat.citation.CitationResolver;
import com.finalysis.chat.outbound.AccountIdentityQuery;
import com.finalysis.chat.tool.AccountAliasQuery;
import com.finalysis.chat.tool.CategoryNamesQuery;
import com.finalysis.chat.tool.ChatTool;
import com.finalysis.chat.tool.DataNotesReader;
import com.finalysis.chat.tool.MerchantSearchQuery;
import com.finalysis.chat.tool.ToolArguments;
import com.finalysis.chat.tool.ToolName;
import com.finalysis.chat.tool.ToolRead;
import com.finalysis.chat.tool.ToolUsingAlertOnlyHelper;
import com.finalysis.chat.tool.ToolUsingQueryServices;
import com.finalysis.chat.tool.ToolUsingSpringAi;
import com.finalysis.chat.tool.ToolUsingTheConversationStore;
import com.finalysis.chat.tool.ToolUsingTheDismissal;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SEC-03 for the chatbot (docs/plans/phase-6.md §9, §18 decision 5): the AI can query data but never change it.
 * <ul>
 *   <li>The tools ({@code chat.tool}) depend only on the analytics and alert query services, their records and enums,
 *       the shared {@code api} types, Spring's core, transaction and JDBC support, Jackson and the JDK. No Spring AI, no
 *       writer, no alert-only analytics helper.</li>
 *   <li>Nothing in {@code chat} depends on {@code categorize} (the redaction classes move to {@code ai.outbound} in
 *       step 2), on the ingestion, review, transfer, transaction or account packages, or on the alert dismissal.</li>
 *   <li>The tools never reach the conversation store, the one writer of the chat tables (step 5).</li>
 *   <li>No other feature depends on chat.</li>
 * </ul>
 * Step 1 writes the rules before the code they govern: until step 2 the packages hold only their {@code package-info},
 * so the rules allow empty matches, and each one is shown to catch a violation on fixture classes. Step 3's tools must
 * pass them unchanged; the reflection and Postgres checks of §9 come with them.
 */
class ChatReadOnlyTest {

    static final String CHAT = "com.finalysis.chat..";
    static final String CHAT_TOOL = "com.finalysis.chat.tool..";

    /** The query services the tools adapt (phase-5.md §8), plus Phase 6's comparison service (§3.3, step 2). */
    static final Set<String> QUERY_SERVICES = Set.of(
            "com.finalysis.analytics.MonthlyTotalsService",
            "com.finalysis.analytics.CategoryTotalsService",
            "com.finalysis.analytics.AccountTotalsService",
            "com.finalysis.analytics.MerchantTotalsService",
            "com.finalysis.analytics.TrendsService",
            "com.finalysis.analytics.DrillDownService",
            "com.finalysis.analytics.RecurringChargesService",
            "com.finalysis.analytics.CoverageService",
            "com.finalysis.analytics.ComparisonService",
            "com.finalysis.alert.AlertQueryService");

    /** Allowed dependencies of a tool. */
    static final DescribedPredicate<JavaClass> ALLOWED_FOR_TOOLS = DescribedPredicate.describe(
            "the query services, their records and enums, api types, Spring core/transaction/JDBC, Jackson or the JDK",
            ChatReadOnlyTest::allowedForTools);

    static final ArchRule TOOLS_USE_QUERY_SERVICES_ONLY = noClasses()
            .that().resideInAPackage(CHAT_TOOL)
            .should().dependOnClassesThat(DescribedPredicate.not(ALLOWED_FOR_TOOLS))
            .allowEmptyShould(true)
            .because("a tool is a thin, read-only adapter over the Phase 5 query services (phase-6.md §3, §9)");

    static final ArchRule TOOLS_HAVE_NO_AI = noClasses()
            .that().resideInAPackage(CHAT_TOOL)
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai..", "com.finalysis.ai..")
            .allowEmptyShould(true)
            .because("tools read data; only the chat loop talks to a model");

    static final ArchRule CHAT_USES_NO_WRITER = noClasses()
            .that().resideInAPackage(CHAT)
            .should().dependOnClassesThat().resideInAnyPackage("com.finalysis.categorize..", "com.finalysis.ingestion..",
                    "com.finalysis.review..", "com.finalysis.transfer..", "com.finalysis.transaction..",
                    "com.finalysis.account..")
            .orShould().dependOnClassesThat().belongToAnyOf(AlertDismissalService.class)
            .allowEmptyShould(true)
            .because("chat tools are read-only (SEC-03); corrections, dismissals, transfers and imports are user "
                    + "actions over their own endpoints, and the redaction classes live in ai.outbound (§18 decision 5)");

    static final ArchRule TOOLS_NEVER_REACH_THE_STORE = noClasses()
            .that().resideInAPackage(CHAT_TOOL)
            .should().dependOnClassesThat().haveSimpleName("ChatConversationStore")
            .allowEmptyShould(true)
            .because("the conversation store is the chat tables' one writer, outside the tools' read-only transactions");

    /** A JDBC call that can write: an update, a batch or a statement executed as is. */
    static final DescribedPredicate<JavaMethodCall> JDBC_WRITE = DescribedPredicate.describe(
            "a JDBC update, batchUpdate or execute",
            call -> Set.of("update", "batchUpdate", "execute").contains(call.getName())
                    && (call.getTargetOwner().isAssignableTo(JdbcClient.StatementSpec.class)
                    || call.getTargetOwner().isAssignableTo(JdbcOperations.class)
                    || call.getTargetOwner().isAssignableTo(NamedParameterJdbcOperations.class)));

    static final ArchRule ONLY_THE_STORE_WRITES = noClasses()
            .that().resideInAPackage(CHAT).and().doNotHaveSimpleName("ChatConversationStore")
            .should().callMethodWhere(JDBC_WRITE)
            .allowEmptyShould(true)
            .because("ChatConversationStore is the chat tables' one writer (phase-6.md §7.3, §9); everything else in "
                    + "chat reads");

    static final ArchRule NOTHING_DEPENDS_ON_CHAT = noClasses()
            .that().resideOutsideOfPackage(CHAT)
            .should().dependOnClassesThat().resideInAPackage(CHAT)
            .because("chat is a leaf: it reads the other features, none of them knows it");

    private static JavaClasses app;

    @BeforeAll
    static void importApp() {
        app = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finalysis");
        // The import must see the code the rules are about.
        assertThat(app.contain(AlertQueryService.class)).isTrue();
        assertThat(app.contain(AlertDismissalService.class)).isTrue();
    }

    @Test
    void toolsUseOnlyTheQueryServices() {
        TOOLS_USE_QUERY_SERVICES_ONLY.check(app);
        TOOLS_HAVE_NO_AI.check(app);
    }

    @Test
    void chatUsesNoWriterAndNothingFromCategorize() {
        CHAT_USES_NO_WRITER.check(app);
        TOOLS_NEVER_REACH_THE_STORE.check(app);
    }

    /** Step 5: the store exists, and it is the only class in chat that writes through JDBC. */
    @Test
    void onlyTheConversationStoreWrites() {
        assertThat(app.contain(ChatConversationStore.class)).isTrue();
        ONLY_THE_STORE_WRITES.check(app);
        assertThat(ONLY_THE_STORE_WRITES.evaluate(new ClassFileImporter().importClasses(ChatConversationStore.class))
                .hasViolation()).as("the store itself may write").isFalse();
        assertThat(ONLY_THE_STORE_WRITES.evaluate(new ClassFileImporter().importClasses(ChatWritingOnItsOwn.class))
                .hasViolation()).isTrue();
    }

    /**
     * Chat's own SQL (aliases, category names, identities, citations, the merchant index) reads only: every public query method is
     * read-only, and so is every public method of the citation resolver, which also runs drill-downs.
     */
    @Test
    void chatsOwnQueriesAreReadOnly() {
        List<Class<?>> queries = List.of(AccountAliasQuery.class, CategoryNamesQuery.class, AccountIdentityQuery.class,
                CitationQuery.class, MerchantSearchQuery.class);
        assertThat(app.stream().filter(c -> c.getPackageName().startsWith("com.finalysis.chat")
                && c.getSimpleName().endsWith("Query")).map(JavaClass::reflect).toList())
                .containsExactlyInAnyOrderElementsOf(queries);
        for (Class<?> query : queries) {
            for (Method method : query.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())) {
                    Transactional tx = method.getAnnotation(Transactional.class);
                    assertThat(tx).as(query.getSimpleName() + "." + method.getName()).isNotNull();
                    assertThat(tx.readOnly()).as(query.getSimpleName() + "." + method.getName()).isTrue();
                }
            }
        }
    }

    @Test
    void citationsAreResolvedReadOnly() {
        for (Method method : CitationResolver.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())) {
                Transactional tx = method.getAnnotation(Transactional.class);
                assertThat(tx).as("CitationResolver." + method.getName()).isNotNull();
                assertThat(tx.readOnly()).as("CitationResolver." + method.getName()).isTrue();
            }
        }
    }

    /**
     * §9.2: every tool's read is read-only in its own declaration too, besides the executor's transaction, and the
     * application's tools are exactly the registry's names.
     */
    @Test
    void everyToolReadIsReadOnly() throws NoSuchMethodException {
        List<Class<?>> tools = app.stream().filter(c -> c.isAssignableTo(ChatTool.class) && !c.isInterface())
                .<Class<?>>map(JavaClass::reflect).toList();
        assertThat(tools).hasSize(ToolName.values().length);
        for (Class<?> tool : tools) {
            Transactional tx = tool.getMethod("read", ToolArguments.class).getAnnotation(Transactional.class);
            assertThat(tx).as(tool.getSimpleName()).isNotNull();
            assertThat(tx.readOnly()).as(tool.getSimpleName()).isTrue();
            assertThat(tx.isolation()).as(tool.getSimpleName()).isEqualTo(Isolation.REPEATABLE_READ);
        }
        Transactional notes = DataNotesReader.class.getMethod("notes", ToolRead.class)
                .getAnnotation(Transactional.class);
        assertThat(notes.readOnly()).isTrue();
    }

    @Test
    void nothingDependsOnChat() {
        NOTHING_DEPENDS_ON_CHAT.check(app);
    }

    /** Each rule catches a violation, and a tool that only calls the query services passes all of them. */
    @Test
    void theRulesFailOnAViolation() {
        JavaClasses dismissal = new ClassFileImporter().importClasses(ToolUsingTheDismissal.class);
        JavaClasses helper = new ClassFileImporter().importClasses(ToolUsingAlertOnlyHelper.class);
        JavaClasses springAi = new ClassFileImporter().importClasses(ToolUsingSpringAi.class);
        JavaClasses store = new ClassFileImporter().importClasses(ToolUsingTheConversationStore.class);
        JavaClasses corrections = new ClassFileImporter().importClasses(ChatUsingCorrections.class);
        JavaClasses categorize = new ClassFileImporter().importClasses(ChatUsingCategorize.class);
        JavaClasses chatUser = new ClassFileImporter().importClasses(com.finalysis.alert.UsesChat.class);
        JavaClasses services = new ClassFileImporter().importClasses(ToolUsingQueryServices.class);

        assertThat(TOOLS_USE_QUERY_SERVICES_ONLY.evaluate(dismissal).hasViolation()).isTrue();
        assertThat(CHAT_USES_NO_WRITER.evaluate(dismissal).hasViolation()).isTrue();
        assertThat(TOOLS_USE_QUERY_SERVICES_ONLY.evaluate(helper).hasViolation()).isTrue();
        assertThat(TOOLS_USE_QUERY_SERVICES_ONLY.evaluate(springAi).hasViolation()).isTrue();
        assertThat(TOOLS_HAVE_NO_AI.evaluate(springAi).hasViolation()).isTrue();
        assertThat(TOOLS_NEVER_REACH_THE_STORE.evaluate(store).hasViolation()).isTrue();
        assertThat(CHAT_USES_NO_WRITER.evaluate(corrections).hasViolation()).isTrue();
        assertThat(CHAT_USES_NO_WRITER.evaluate(categorize).hasViolation()).isTrue();
        assertThat(NOTHING_DEPENDS_ON_CHAT.evaluate(chatUser).hasViolation()).isTrue();

        assertThat(TOOLS_USE_QUERY_SERVICES_ONLY.evaluate(services).hasViolation())
                .as("the query services, their records and enums are the contract").isFalse();
        assertThat(TOOLS_HAVE_NO_AI.evaluate(services).hasViolation()).isFalse();
        assertThat(CHAT_USES_NO_WRITER.evaluate(services).hasViolation()).isFalse();
        assertThat(TOOLS_NEVER_REACH_THE_STORE.evaluate(services).hasViolation()).isFalse();
    }

    private static boolean allowedForTools(JavaClass target) {
        String name = target.getName();
        if (target.isPrimitive() || target.isArray() && allowedForTools(target.getBaseComponentType())) {
            return true;
        }
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.")
                || name.startsWith("com.fasterxml.jackson.") || name.startsWith("tools.jackson.")
                || name.startsWith("com.finalysis.chat.tool.") || name.startsWith("com.finalysis.api.")) {
            return true;
        }
        for (String spring : new String[] {"core", "beans", "context", "stereotype", "transaction", "jdbc", "dao",
                "util", "lang"}) {
            if (name.startsWith("org.springframework." + spring + ".")) {
                return true;
            }
        }
        if (name.startsWith("com.finalysis.analytics.") || name.startsWith("com.finalysis.alert.")) {
            return QUERY_SERVICES.contains(name) || target.isRecord() || target.isEnum()
                    || name.equals("com.finalysis.alert.AlertDetails");
        }
        return false;
    }

    /** A rule fixture: a chat class writing a chat table itself instead of through the store. */
    static class ChatWritingOnItsOwn {

        void write(JdbcClient jdbc) {
            jdbc.sql("DELETE FROM chat_turn").update();
        }
    }

    @SuppressWarnings("unused")
    static class ChatUsingCorrections {
        private com.finalysis.categorize.CorrectionService corrections;
    }

    @SuppressWarnings("unused")
    static class ChatUsingCategorize {
        private com.finalysis.categorize.CategorizationService categorization;
    }
}
