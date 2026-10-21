package com.finalysis.chat.eval;

import com.finalysis.chat.eval.ChatEvalGuards.EvalRefusedException;
import com.finalysis.chat.eval.ChatEvalGuards.GitState;
import com.finalysis.chat.eval.ChatEvalResults.Run;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import com.finalysis.support.NoCloudAiGuard;
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
 * cd backend &amp;&amp; ./mvnw test -Pchat-eval -Deval.set=holdout -Deval.holdout.confirm=true -Deval.model=&lt;id&gt;
 * </pre>
 *
 * <p>Before the context, the database or any model is touched ({@code @BeforeAll}): the set, the model and its price
 * (the price file must list it), and for the holdout {@link ChatEvalGuards} (pinned hash, confirmation, no earlier chat
 * holdout results, clean tree). The app's own limits apply to every turn, with the model's price from the price file;
 * only the daily cap is replaced by the run budget {@code -Deval.max-cost} (dollars), since a run is about 70
 * conversations on one pinned day. Only synthetic data is sent, and only what the chat guard allows.
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

    static final Path REPOSITORY = ImportTestSupport.SAMPLE_DATA.getParent();
    static final Path RESULTS = REPOSITORY.resolve("docs/eval");
    static final String RUN_PROPERTY = "eval.run";
    static final String RUN_QUESTIONS = "questions";

    private static String set;
    private static String model;
    private static ChatEvalPrices prices;
    private static BigDecimal maxCost;
    private static GitState git;
    private static Instant startedAt;

    private final ChatEvalRunner runner;

    ChatLiveEvalIT(ChatEvalRunner runner) {
        this.runner = runner;
    }

    @BeforeAll
    static void guards() {
        set = ChatEvalGuards.set(System.getProperty("eval.set", ""));
        prices = ChatEvalPrices.load();
        model = ChatEvalGuards.model(System.getProperty("eval.model", ""), prices);
        maxCost = new BigDecimal(System.getProperty("eval.max-cost", "5.00"));
        if (set.equals(ChatEvalGuards.HOLDOUT)) {
            git = ChatEvalGuards.checkHoldout(setFile(set), Boolean.getBoolean(ChatEvalGuards.CONFIRM_PROPERTY),
                    RESULTS, () -> GitState.read(REPOSITORY));
        } else {
            git = GitState.readOrUnknown(REPOSITORY);
        }
        startedAt = Instant.now();
    }

    /** The model under evaluation, priced from the price file so the turn cost limits use the same figures. */
    @DynamicPropertySource
    static void model(DynamicPropertyRegistry registry) {
        registry.add("finalysis.ai.roles.main", () -> model);
        registry.add("finalysis.ai.prices[" + model + "].input-per-million",
                () -> prices.chat(model).orElseThrow().inputPerMillionUsd());
        registry.add("finalysis.ai.prices[" + model + "].output-per-million",
                () -> prices.chat(model).orElseThrow().outputPerMillionUsd());
    }

    @Test
    void evaluate() throws Exception {
        ChatEvalSet questions = ChatEvalSet.load(setFile(set));
        Run run = runner.run(questions, model, prices, maxCost);
        ChatEvalReport report = ChatEvalReport.of(git, startedAt, questions, run);
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
