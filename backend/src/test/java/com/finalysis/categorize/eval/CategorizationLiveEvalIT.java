package com.finalysis.categorize.eval;

import com.finalysis.support.EvalDatabaseGuard;
import com.finalysis.vault.PlainDatabaseConnection;
import com.finalysis.vault.VaultService;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.finalysis.categorize.eval.EvalGuards.EvalRefusedException;
import com.finalysis.categorize.eval.EvalGuards.GitState;
import com.finalysis.categorize.eval.EvalRunner.EvalInputs;
import com.finalysis.categorize.eval.EvalRunner.EvalResult;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The live accuracy evaluation (CAT-05; docs/plans/phase-4.md §10, step 9a notes): the dev or the holdout set through
 * the four variants of {@link EvalRunner}, against the cloud models of the {@code aws} profile, on a fresh
 * Testcontainers database. Never part of {@code ./mvnw verify}: surefire runs {@code *IT} classes only under the
 * {@code ai-eval} profile, which also selects the {@value NoCloudAiGuard#EVAL_TAG} tag and sets
 * {@code -D}{@value NoCloudAiGuard#EVAL_PROPERTY}{@code =true}, the only switch that lifts {@link NoCloudAiGuard} for
 * this class.
 * <pre>
 * cd backend &amp;&amp; ./mvnw test -Pai-eval -Deval.set=dev -Deval.min-confidence=0.80 \
 *     -Deval.direct-min-similarity=0.97
 * cd backend &amp;&amp; ./mvnw test -Pai-eval -Deval.set=holdout -Deval.holdout.confirm=true \
 *     -Deval.min-confidence=&lt;chosen on dev&gt; -Deval.direct-min-similarity=&lt;chosen on dev&gt;
 * </pre>
 *
 * <p>A holdout run first passes {@link EvalGuards} (pinned hash, confirmation, no earlier holdout results, clean
 * working tree), in {@code @BeforeAll}, before the context, the database or any model is touched. Only synthetic data
 * is sent, and only what {@code OutboundPayloadGuard} allows. Results go to {@code docs/eval/} and the console.
 */
@Tag(NoCloudAiGuard.EVAL_TAG)
@EnabledIfSystemProperty(named = NoCloudAiGuard.EVAL_PROPERTY, matches = "true")
@IntegrationTest
@ActiveProfiles("aws")
@Import({EvalSeeder.class, EvalRunner.class})
class CategorizationLiveEvalIT {

    private final PlainDatabaseConnection plainDatabase;
    private final VaultService vault;
    private final PostgreSQLContainer postgres;

    static final Path REPOSITORY = ImportTestSupport.SAMPLE_DATA.getParent();
    static final Path RESULTS = REPOSITORY.resolve("docs/eval");
    static final String DEV = "dev";
    static final String HOLDOUT = "holdout";

    private static String set;
    private static EvalInputs inputs;
    private static GitState git;
    private static Instant startedAt;

    private final EvalRunner runner;

    CategorizationLiveEvalIT(EvalRunner runner, PlainDatabaseConnection plainDatabase,
            VaultService vault, PostgreSQLContainer postgres) {
        this.runner = runner;
        this.plainDatabase = plainDatabase;
        this.vault = vault;
        this.postgres = postgres;
    }

    @BeforeAll
    static void guards() {
        set = System.getProperty("eval.set", "");
        if (!List.of(DEV, HOLDOUT).contains(set)) {
            throw new EvalRefusedException("Choose the set: -Deval.set=dev or -Deval.set=holdout");
        }
        Path file = setFile(set);
        if (set.equals(HOLDOUT)) {
            git = EvalGuards.checkHoldout(file, Boolean.getBoolean(EvalGuards.CONFIRM_PROPERTY), RESULTS,
                    () -> GitState.read(REPOSITORY));
        } else {
            git = GitState.readOrUnknown(REPOSITORY);
        }
        inputs = new EvalInputs(new BigDecimal(required("eval.min-confidence")),
                Double.parseDouble(required("eval.direct-min-similarity")), set.equals(DEV));
        startedAt = Instant.now();
    }

    @Test
    void evaluate() throws Exception {
        EvalResult result = runner.run(EvalSet.load(set, setFile(set)), inputs, EvalPrices.load());
        EvalReport report = EvalReport.of(git, startedAt, result);
        EvalDatabaseGuard.check(plainDatabase, vault.status(), postgres);
        List<Path> files = report.write(RESULTS);
        System.out.println(report.consoleSummary());
        System.out.println("Results: " + files.get(0) + " and " + files.get(1));
    }

    private static Path setFile(String name) {
        return ImportTestSupport.SAMPLE_DATA.resolve("categorization/" + name + ".csv");
    }

    private static String required(String property) {
        String value = System.getProperty(property, "");
        if (value.isBlank()) {
            throw new EvalRefusedException("Set -D" + property);
        }
        return value;
    }
}
