package com.finalysis.chat.eval;

import com.finalysis.support.EvalDatabaseGuard;
import com.finalysis.vault.PlainDatabaseConnection;
import com.finalysis.vault.VaultService;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.context.TestPropertySource;

/**
 * The retrieval-only sweep of the search floor (docs/plans/phase-6.md §4.3, step 8), on the dev set only: the dev
 * search questions' queries through the real search path at floors 0.30 to 0.60 ({@link SearchSweep}), with the
 * embedding model of the {@code aws} profile and no chat call (checked). Results in {@code docs/eval/}.
 * <pre>
 * cd backend &amp;&amp; ./mvnw test -Pchat-eval -Deval.run=sweep -Deval.set=dev
 * </pre>
 * It refuses any set but dev before anything is started, and never opens the holdout file. Same switches as
 * {@link ChatLiveEvalIT}: the {@code chat-eval} profile, tag and property.
 */
@Tag(NoCloudAiGuard.CHAT_EVAL_TAG)
@EnabledIfSystemProperty(named = NoCloudAiGuard.CHAT_EVAL_PROPERTY, matches = "true")
@EnabledIfSystemProperty(named = ChatLiveEvalIT.RUN_PROPERTY, matches = ChatSearchSweepIT.RUN_SWEEP)
@IntegrationTest
@AutoConfigureMockMvc
@ActiveProfiles("aws")
@Import({ChatEvalBeans.class, ChatEvalClock.class, ChatSearchSweepIT.SweepBeans.class})
@TestPropertySource(properties = {
        "finalysis.ai.default-path=CLOUD",
        "finalysis.chat.search.min-similarity=0.30",
        "finalysis.ai.categorization.run-after-import=false"})
class ChatSearchSweepIT {

    private final PlainDatabaseConnection plainDatabase;
    private final VaultService vault;
    private final PostgreSQLContainer postgres;

    static final String RUN_SWEEP = "sweep";

    private static GitState git;
    private static Instant startedAt;

    private final SearchSweepRunner runner;
    private final ChatCallRecorder recorder;
    private final ConfigurableEnvironment environment;

    ChatSearchSweepIT(SearchSweepRunner runner, ChatCallRecorder recorder, ConfigurableEnvironment environment,
            PlainDatabaseConnection plainDatabase,
            VaultService vault, PostgreSQLContainer postgres) {
        this.runner = runner;
        this.plainDatabase = plainDatabase;
        this.vault = vault;
        this.postgres = postgres;
        this.recorder = recorder;
        this.environment = environment;
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
        git = GitState.readOrUnknown(ChatLiveEvalIT.REPOSITORY);
        startedAt = Instant.now();
    }

    @Test
    void sweep() throws Exception {
        assertThat(environment.getProperty("finalysis.chat.search.min-similarity", Double.class))
                .isEqualTo(SearchSweep.FLOORS.getFirst().doubleValue());
        ChatEvalSet dev = ChatEvalSet.load(ImportTestSupport.SAMPLE_DATA.resolve("chat/dev.yml"));
        SearchSweepRunner.Sweep sweep = runner.run(dev, SearchSweepRunner.loadQueries());
        assertThat(recorder.totalCalls()).as("the sweep calls no chat model").isZero();

        SearchSweepReport report = new SearchSweepReport(git.commit(), git.dirty(), startedAt.toString(),
                environment.getProperty("finalysis.ai.roles.embedding"),
                ChatEvalRunner.settings(environment), sweep);
        EvalDatabaseGuard.check(plainDatabase, vault.status(), postgres);
        List<Path> files = report.write(ChatLiveEvalIT.RESULTS);
        System.out.println(report.summaryTable());
        System.out.println("Results: " + files.get(0) + " and " + files.get(1));
    }
}
