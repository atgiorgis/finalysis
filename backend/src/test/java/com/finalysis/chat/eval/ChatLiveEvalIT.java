package com.finalysis.chat.eval;

import com.finalysis.ai.AiProviders;
import com.finalysis.ai.AiPathSettings;
import com.finalysis.ai.AiPath;
import com.finalysis.support.EvalDatabaseGuard;
import com.finalysis.vault.PlainDatabaseConnection;
import com.finalysis.vault.VaultService;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.finalysis.chat.eval.ChatEvalGuards.EvalRefusedException;
import com.finalysis.chat.eval.ChatEvalGuards.GitState;
import com.finalysis.chat.eval.ChatEvalResults.Run;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import com.finalysis.support.NoCloudAiGuard;
import java.time.Duration;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The live chat evaluation (docs/plans/phase-6.md §13.2, step 8): the dev or holdout question set through the real chat
 * path against the Bedrock model {@code -Deval.model}, on a fresh Testcontainers database, scored deterministically
 * ({@link ChatEvalScoring}), results in {@code docs/eval/}. Never part of {@code ./mvnw verify}: surefire runs
 * {@code *IT} classes only under the {@code chat-eval} profile, which also selects the
 * {@value NoCloudAiGuard#CHAT_EVAL_TAG} tag and sets {@code -D}{@value NoCloudAiGuard#CHAT_EVAL_PROPERTY}{@code =true},
 * the only switch that lifts {@link NoCloudAiGuard} for this class.
 * <pre>
 * cd backend &amp;&amp; ./mvnw test -Pchat-eval -Deval.set=dev -Deval.model=&lt;id&gt;
 * cd backend &amp;&amp; ./mvnw test -Pchat-eval -Deval.set=dev -Deval.model=&lt;id&gt; -Deval.maxTurnCost=0.10
 * cd backend &amp;&amp; ./mvnw test -Pchat-eval -Deval.set=dev -Deval.model=&lt;id&gt; -Deval.maxTurnCost=sized
 * cd backend &amp;&amp; ./mvnw test -Pchat-eval -Deval.set=holdout -Deval.holdout.confirm=true -Deval.model=&lt;id&gt;
 * cd backend &amp;&amp; ./mvnw test -Pchat-eval -Deval.set=dev -Deval.provider=ollama -Deval.baseUrl=http://&lt;host&gt;:11434 -Deval.model=&lt;ollama tag&gt;
 * cd backend &amp;&amp; ./mvnw test -Pchat-eval -Deval.set=dev -Deval.provider=ollama -Deval.baseUrl=http://127.0.0.1:11434 -Deval.model=&lt;tag&gt; -Deval.questions=d-l01,d-c01
 * </pre>
 *
 * <p>Ollama (docs/plans/phase-9.md §4.3): {@code -Deval.provider=ollama} with {@code -Deval.baseUrl}, whose host gives
 * the path (loopback: Local; a Tailscale name: Self-hosted) and is the only part recorded ({@link ChatEvalTarget}). The
 * run switches the context to that path and refuses to start unless Ollama answers with the model pulled. The turn
 * cost limit is off and the run budget doesn't apply (no token cost); the report says so. {@code -Deval.questions}
 * runs a named subset, marked in the file name and header, never a score.
 *
 * <p>Before the context, the database or any model is touched ({@code @BeforeAll}): the set, the model and its price
 * (the price file must list it), and for the holdout {@link ChatEvalGuards} (pinned hash, confirmation, no earlier chat
 * holdout results, clean tree). The app's own limits apply to every turn, with the model's price from the price file;
 * only the daily cap is replaced by the run budget {@code -Deval.max-cost} (dollars), since a run is about 70
 * conversations on one pinned day. {@code -Deval.maxTurnCost} (dollars, step 8d) sets the turn cost limit for this run
 * only, recorded in the report's header; {@code sized} (Phase 9 step 9a) computes it from the model's price so it allows
 * as many worst-case rounds as the app's limit allows Sonnet 4.6 ({@link ChatEvalTurnCost}); the context's startup check refuses a model whose largest round passes the
 * limit before anything is seeded or sent. Only synthetic data is sent, and only what the chat guard allows.
 */
@Tag(NoCloudAiGuard.CHAT_EVAL_TAG)
@EnabledIfSystemProperty(named = NoCloudAiGuard.CHAT_EVAL_PROPERTY, matches = "true")
@EnabledIfSystemProperty(named = ChatLiveEvalIT.RUN_PROPERTY, matches = ChatLiveEvalIT.RUN_QUESTIONS)
@IntegrationTest
@AutoConfigureMockMvc
@ActiveProfiles("aws")
@Import({ChatEvalBeans.class, ChatEvalClock.class})
@TestPropertySource(properties = {
        "finalysis.chat.max-daily-cost=0",
        "finalysis.ai.categorization.run-after-import=false"})
class ChatLiveEvalIT {

    private final PlainDatabaseConnection plainDatabase;
    private final VaultService vault;
    private final PostgreSQLContainer postgres;

    static final Path REPOSITORY = ImportTestSupport.SAMPLE_DATA.getParent();
    static final Path RESULTS = REPOSITORY.resolve("docs/eval");
    static final String RUN_PROPERTY = "eval.run";
    static final String RUN_QUESTIONS = "questions";

    private static String set;
    private static ChatEvalTarget target;
    private static List<String> subset;
    private static String model;
    private static ChatEvalPrices prices;
    private static BigDecimal maxCost;
    private static BigDecimal maxTurnCost;
    private static GitState git;
    private static Instant startedAt;

    private final ChatEvalRunner runner;
    private final AiProviders providers;

    ChatLiveEvalIT(ChatEvalRunner runner, AiProviders providers, PlainDatabaseConnection plainDatabase,
            VaultService vault, PostgreSQLContainer postgres) {
        this.runner = runner;
        this.providers = providers;
        this.plainDatabase = plainDatabase;
        this.vault = vault;
        this.postgres = postgres;
    }

    @BeforeAll
    static void guards() {
        set = ChatEvalGuards.set(System.getProperty("eval.set", ""));
        target = ChatEvalTarget.parse(System.getProperty(ChatEvalTarget.PROVIDER_PROPERTY),
                System.getProperty(ChatEvalTarget.BASE_URL_PROPERTY));
        subset = ChatEvalTarget.subset(System.getProperty(ChatEvalTarget.QUESTIONS_PROPERTY),
                ChatEvalSet.load(setFile(set)));
        prices = ChatEvalPrices.load();
        maxCost = new BigDecimal(System.getProperty("eval.max-cost", "5.00"));
        String turnCost = System.getProperty(ChatEvalGuards.MAX_TURN_COST_PROPERTY);
        if (target.ollama()) {
            model = System.getProperty("eval.model", "").strip();
            if (model.isEmpty()) {
                throw new ChatEvalGuards.EvalRefusedException("Choose the Ollama model: -Deval.model=<tag>");
            }
            if (turnCost != null) {
                throw new ChatEvalGuards.EvalRefusedException("-D" + ChatEvalGuards.MAX_TURN_COST_PROPERTY
                        + " has no effect on Ollama: the turn cost limit is off there (no token cost)");
            }
            maxTurnCost = null;
        } else {
            model = ChatEvalGuards.model(System.getProperty("eval.model", ""), prices);
            maxTurnCost = ChatEvalGuards.maxTurnCost(turnCost, prices, model);
        }
        if (turnCost != null && turnCost.strip().equals(ChatEvalTurnCost.SIZED)) {
            System.out.println("Turn cost limit sized from the price of " + model + ": $" + maxTurnCost.toPlainString());
        }
        if (set.equals(ChatEvalGuards.HOLDOUT)) {
            git = ChatEvalGuards.checkHoldout(setFile(set), Boolean.getBoolean(ChatEvalGuards.CONFIRM_PROPERTY),
                    RESULTS, () -> GitState.read(REPOSITORY));
        } else {
            git = GitState.readOrUnknown(REPOSITORY);
        }
        startedAt = Instant.now();
    }

    /**
     * The model under evaluation. On Bedrock it is priced from the price file so the turn cost limits use the same
     * figures; on Ollama it is the chat model tag, and the path is switched to in {@link #evaluate()}.
     */
    @DynamicPropertySource
    static void model(DynamicPropertyRegistry registry) {
        if (target.ollama()) {
            registry.add("finalysis.ai.ollama.models.chat", () -> model);
            registry.add("finalysis.ai.default-path", () -> "OFF");
            return;
        }
        registry.add("finalysis.ai.default-path", () -> "CLOUD");
        registry.add("finalysis.ai.roles.main", () -> model);
        prices.register(registry, model);
        if (maxTurnCost != null) {
            registry.add("finalysis.chat.max-turn-cost", maxTurnCost::toPlainString);
        }
    }

    @Test
    void evaluate() throws Exception {
        ChatEvalSet questions = ChatEvalTarget.only(ChatEvalSet.load(setFile(set)), subset);
        ChatEvalTarget runTarget = target.withSubset(subset);
        if (target.ollama()) {
            AiProviders.State state = providers.switchTo(new AiPathSettings.Selection(target.path(),
                    target.path() == AiPath.SELF_HOSTED ? target.host() : null, null), Duration.ofSeconds(10));
            if (!state.reachable()) {
                throw new ChatEvalGuards.EvalRefusedException("Ollama at " + target.host() + " can't run the "
                        + "evaluation: " + state.reason() + "; nothing was sent");
            }
            runTarget = runTarget.withOllamaVersion(state.ollamaVersion());
        }
        Run run = runner.run(questions, model, prices, maxCost, maxTurnCost != null, runTarget);
        ChatEvalReport report = ChatEvalReport.of(git, startedAt, questions, run);
        EvalDatabaseGuard.check(plainDatabase, vault.status(), postgres);
        List<Path> files = report.write(RESULTS);
        System.out.println(report.consoleSummary());
        System.out.println("Results: " + files.get(0) + " and " + files.get(1));
    }

    private static Path setFile(String name) {
        if (!List.of(ChatEvalGuards.DEV, ChatEvalGuards.HOLDOUT).contains(name)) {
            throw new EvalRefusedException("Unknown set " + name);
        }
        return ImportTestSupport.SAMPLE_DATA.resolve("chat/" + name + ".yml");
    }
}
