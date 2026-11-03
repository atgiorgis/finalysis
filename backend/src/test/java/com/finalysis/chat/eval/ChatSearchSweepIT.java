package com.finalysis.chat.eval;

import com.finalysis.support.EvalDatabaseGuard;
import com.finalysis.vault.PlainDatabaseConnection;
import com.finalysis.vault.VaultService;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.ai.AiProviders;
import com.finalysis.chat.ToolExecutor;
import com.finalysis.chat.eval.ChatEvalGuards.EvalRefusedException;
import com.finalysis.chat.eval.ChatEvalGuards.GitState;
import com.finalysis.chat.outbound.OutboundTurnFactory;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import com.finalysis.support.NoCloudAiGuard;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The retrieval-only sweep of the search floor (docs/plans/phase-6.md §4.3, step 8), on the dev set only: the dev
 * search questions' queries through the real search path at floors 0.30 to 0.60 ({@link SearchSweep}), with the
 * embedding model of the run's path and no chat call (checked). Results in {@code docs/eval/}.
 * <pre>
 * cd backend &amp;&amp; ./mvnw test -Pchat-eval -Deval.run=sweep -Deval.set=dev
 * cd backend &amp;&amp; ./mvnw test -Pchat-eval -Deval.run=sweep -Deval.set=dev -Deval.provider=ollama -Deval.baseUrl=http://&lt;host&gt;:11434 -Deval.model=&lt;ollama tag&gt;
 * </pre>
 * The same provider selection as the dev run ({@link ChatEvalTarget}, 9c-10): Bedrock's Titan by default; with
 * {@code -Deval.provider=ollama}, the path the base URL's host gives, its embedding model ({@code nomic-embed-text})
 * builds the index and embeds the queries. {@code -Deval.model} names the chat model the server has pulled: the path's
 * reachability check needs it, and the sweep never calls it. It refuses any set but dev before anything is started,
 * and never opens the holdout file. Same switches as {@link ChatLiveEvalIT}: the {@code chat-eval} profile, tag and
 * property.
 */
@Tag(NoCloudAiGuard.CHAT_EVAL_TAG)
@EnabledIfSystemProperty(named = NoCloudAiGuard.CHAT_EVAL_PROPERTY, matches = "true")
@EnabledIfSystemProperty(named = ChatLiveEvalIT.RUN_PROPERTY, matches = ChatSearchSweepIT.RUN_SWEEP)
@IntegrationTest
@AutoConfigureMockMvc
@ActiveProfiles("aws")
@Import({ChatEvalBeans.class, ChatEvalClock.class, ChatSearchSweepIT.SweepBeans.class})
@TestPropertySource(properties = {
        "finalysis.chat.search.min-similarity=0.30",
        "finalysis.ai.categorization.run-after-import=false"})
class ChatSearchSweepIT {

    static final String RUN_SWEEP = "sweep";

    private static GitState git;
    private static Instant startedAt;
    private static ChatEvalTarget target;
    private static String model;

    private final SearchSweepRunner runner;
    private final ChatCallRecorder recorder;
    private final ConfigurableEnvironment environment;
    private final AiProviders providers;
    private final PlainDatabaseConnection plainDatabase;
    private final VaultService vault;
    private final PostgreSQLContainer postgres;

    ChatSearchSweepIT(SearchSweepRunner runner, ChatCallRecorder recorder, ConfigurableEnvironment environment,
                      AiProviders providers, PlainDatabaseConnection plainDatabase, VaultService vault,
                      PostgreSQLContainer postgres) {
        this.runner = runner;
        this.recorder = recorder;
        this.environment = environment;
        this.providers = providers;
        this.plainDatabase = plainDatabase;
        this.vault = vault;
        this.postgres = postgres;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SweepBeans {

        @Bean
        SearchSweepRunner searchSweepRunner(ChatEvalSeeder seeder, OutboundTurnFactory turns, ToolExecutor executor) {
            return new SearchSweepRunner(seeder, turns, executor);
        }
    }

    @BeforeAll
    static void devOnly() {
        if (!ChatEvalGuards.DEV.equals(System.getProperty("eval.set", ""))) {
            throw new EvalRefusedException("The search sweep runs on the dev set only: -Deval.set=dev");
        }
        target = ChatEvalTarget.parse(System.getProperty(ChatEvalTarget.PROVIDER_PROPERTY),
                System.getProperty(ChatEvalTarget.BASE_URL_PROPERTY));
        model = System.getProperty("eval.model", "").strip();
        if (target.ollama() && model.isEmpty()) {
            throw new EvalRefusedException("Name the chat model the server has pulled: -Deval.model=<tag> (its "
                    + "reachability check needs it; the sweep never calls it)");
        }
        git = GitState.readOrUnknown(ChatLiveEvalIT.REPOSITORY);
        startedAt = Instant.now();
    }

    /** As the dev run: Cloud by default; on Ollama the context starts Off and {@link #sweep()} switches. */
    @DynamicPropertySource
    static void path(DynamicPropertyRegistry registry) {
        if (target.ollama()) {
            registry.add("finalysis.ai.ollama.models.chat", () -> model);
            registry.add("finalysis.ai.default-path", () -> "OFF");
        } else {
            registry.add("finalysis.ai.default-path", () -> "CLOUD");
        }
    }

    @Test
    void sweep() throws Exception {
        SearchSweepReport report = run(runner, recorder, environment, providers, target, git, startedAt);
        EvalDatabaseGuard.check(plainDatabase, vault.status(), postgres);
        List<Path> files = report.write(ChatLiveEvalIT.RESULTS);
        System.out.println(report.summaryTable());
        System.out.println("Results: " + files.get(0) + " and " + files.get(1));
    }

    /**
     * The sweep on the target's path: switched to it first, so the index is built and the queries embedded with that
     * path's embedding model, which the report names. Also run by {@code ChatSearchSweepOllamaTest} against a stub.
     */
    static SearchSweepReport run(SearchSweepRunner runner, ChatCallRecorder recorder,
                                 ConfigurableEnvironment environment, AiProviders providers, ChatEvalTarget target,
                                 GitState git, Instant startedAt) throws Exception {
        assertThat(environment.getProperty("finalysis.chat.search.min-similarity", Double.class))
                .isEqualTo(SearchSweep.FLOORS.getFirst().doubleValue());
        ChatEvalTarget connected = target.connect(providers);
        if (providers.state().path() != connected.path() || providers.state().providers() == null) {
            throw new EvalRefusedException("The context isn't on the " + connected.path() + " path; nothing was sent");
        }
        String embeddingModel = providers.state().providers().embedding().modelId();
        ChatEvalSet dev = ChatEvalSet.load(ImportTestSupport.SAMPLE_DATA.resolve("chat/dev.yml"));
        SearchSweepRunner.Sweep sweep = runner.run(dev, SearchSweepRunner.loadQueries());
        assertThat(recorder.totalCalls()).as("the sweep calls no chat model").isZero();
        return new SearchSweepReport(git.commit(), git.dirty(), startedAt.toString(), connected, embeddingModel,
                ChatEvalRunner.settings(environment), sweep);
    }
}
