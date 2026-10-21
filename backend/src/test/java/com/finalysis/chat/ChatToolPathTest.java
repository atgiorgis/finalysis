package com.finalysis.chat;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ai.outbound.OutboundPayloadGuard;
import com.finalysis.chat.citation.Citation;
import com.finalysis.chat.outbound.CitationLeak;
import com.finalysis.chat.outbound.ChatPayloadGuard;
import com.finalysis.chat.outbound.ToolResultWriter;
import com.finalysis.chat.tool.ChatTool;
import com.finalysis.chat.tool.ToolRegistry;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * A tool's result reaches a payload one way only (docs/plans/phase-6.md §12.1): {@link ToolExecutor} runs the tool,
 * the projection builds its view, {@link ChatPayloadGuard} checks it, and {@link ToolResultWriter} writes only what the
 * guard returned.
 * <ul>
 *   <li>only {@code ToolExecutor} calls {@link ChatTool#read};</li>
 *   <li>only the guard makes a {@link ChatPayloadGuard.Checked};</li>
 *   <li>in chat, only the writer serializes (and the registry, its fixed schemas), so no service record or unchecked
 *       view can be turned into JSON anywhere else;</li>
 *   <li>the writer's public methods take only {@code Checked} values;</li>
 *   <li>citations stay local (CHT-05, §6): nothing that builds or writes a payload depends on {@code chat.citation},
 *       and no citation type can pass the guard.</li>
 * </ul>
 * Each rule is shown to catch a violation. {@code ToolExecutorTest} checks the same path at run time: what is sent for
 * each tool equals its service record projected and checked.
 */
class ChatToolPathTest {

    static final String CHAT = "com.finalysis.chat..";

    /** Step 5 adds the prompt builder (the context block and history), which also writes only checked views. */
    /** The writer (what the model receives), the registry (the schemas) and the store (local JSON columns only). */
    static final Set<Class<?>> SERIALIZERS = Set.of(ToolResultWriter.class, ToolRegistry.class,
            ChatConversationStore.class);

    static final Set<String> WRITE_METHODS = Set.of("writeValueAsString", "writeValueAsBytes", "writeValue",
            "valueToTree", "convertValue", "writer", "writerFor", "writerWithDefaultPrettyPrinter", "writerWithView");

    static final ArchRule ONLY_THE_EXECUTOR_RUNS_TOOLS = noClasses()
            .that().doNotBelongToAnyOf(ToolExecutor.class)
            .should().callMethodWhere(DescribedPredicate.describe("ChatTool.read",
                    (JavaMethodCall call) -> call.getTarget().getOwner().isAssignableTo(ChatTool.class)
                            && call.getTarget().getName().equals("read")))
            .because("a tool runs only inside the executor's read-only transaction, then through projection and guard");

    static final ArchRule ONLY_THE_GUARD_CHECKS = noClasses()
            .that().doNotBelongToAnyOf(ChatPayloadGuard.class)
            .should().callConstructorWhere(DescribedPredicate.describe("new ChatPayloadGuard.Checked",
                    (JavaConstructorCall call) -> call.getTarget().getOwner().isEquivalentTo(
                            ChatPayloadGuard.Checked.class)))
            .because("a Checked value is the guard's word that a view may leave");

    static final ArchRule ONLY_THE_WRITER_SERIALIZES = noClasses()
            .that().resideInAPackage(CHAT)
            .and(DescribedPredicate.describe("are not the writer or the registry",
                    (JavaClass c) -> SERIALIZERS.stream().noneMatch(c::isEquivalentTo)))
            .should().callMethodWhere(DescribedPredicate.describe("a Jackson write",
                    (JavaMethodCall call) -> call.getTarget().getOwner().isAssignableTo(ObjectMapper.class)
                            && WRITE_METHODS.contains(call.getTarget().getName())))
            .because("what the model receives is written from checked views only (phase-6.md §12.1)");

    static final ArchRule CITATIONS_STAY_LOCAL = noClasses()
            .that().resideInAnyPackage("com.finalysis.chat.outbound..", "com.finalysis.chat.tool..")
            .should().dependOnClassesThat().resideInAPackage("com.finalysis.chat.citation..")
            .because("citations hold ids, account names and statement lines: they go to the client, never to the model");

    private static JavaClasses app;

    @BeforeAll
    static void importApp() {
        app = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finalysis");
        assertThat(app.contain(ToolExecutor.class)).isTrue();
    }

    @Test
    void toolsRunOnlyThroughTheExecutor() {
        ONLY_THE_EXECUTOR_RUNS_TOOLS.check(app);
    }

    @Test
    void onlyTheGuardChecksAndOnlyTheWriterWrites() {
        ONLY_THE_GUARD_CHECKS.check(app);
        ONLY_THE_WRITER_SERIALIZES.check(app);
    }

    @Test
    void theWriterTakesOnlyCheckedViews() {
        Method[] methods = Arrays.stream(ToolResultWriter.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers())).toArray(Method[]::new);
        assertThat(methods).isNotEmpty();
        for (Method method : methods) {
            assertThat(method.getParameterTypes()).as(method.getName()).isNotEmpty()
                    .allMatch(type -> type == ChatPayloadGuard.Checked.class);
        }
    }

    @Test
    void citationsNeverReachAPayload() {
        CITATIONS_STAY_LOCAL.check(app);
        ChatPayloadGuard guard = new ChatPayloadGuard(new OutboundPayloadGuard());
        for (Class<?> citation : Citation.class.getPermittedSubclasses()) {
            assertThatThrownBy(() -> guard.classify(citation)).as(citation.getSimpleName())
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> guard.classify(Citation.CitedTxn.class)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theRulesFailOnAViolation() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(Bypass.class);

        assertThat(ONLY_THE_EXECUTOR_RUNS_TOOLS.evaluate(fixtures).hasViolation()).isTrue();
        assertThat(ONLY_THE_GUARD_CHECKS.evaluate(fixtures).hasViolation()).isTrue();
        assertThat(ONLY_THE_WRITER_SERIALIZES.evaluate(fixtures).hasViolation()).isTrue();
        assertThat(CITATIONS_STAY_LOCAL.evaluate(new ClassFileImporter().importClasses(CitationLeak.class))
                .hasViolation()).isTrue();
    }

    /** A rule fixture: runs a tool and sends its record without the projection or the guard. */
    static class Bypass {

        @SuppressWarnings("unused")
        String send(ChatTool tool, ObjectMapper json) {
            ChatPayloadGuard.Checked<Object> forged = new ChatPayloadGuard.Checked<>(new Object(), 1, 0);
            return json.writeValueAsString(tool.read(null)) + forged;
        }
    }
}
