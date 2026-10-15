package com.finalysis.categorize.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.categorize.eval.EvalGuards.EvalRefusedException;
import com.finalysis.categorize.eval.EvalGuards.GitState;
import com.finalysis.support.ImportTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The holdout guard (CAT-05; docs/plans/phase-4.md, step 9a notes). Reads nothing of the holdout but its hash, and
 * never in a way that could tune anything: the guard's own conditions are what is under test.
 */
class EvalGuardsTest {

    private static final Path HOLDOUT = ImportTestSupport.SAMPLE_DATA.resolve("categorization/holdout.csv");
    private static final GitState CLEAN = new GitState("0123456789abcdef0123456789abcdef01234567", false);

    @TempDir
    Path results;

    private final AtomicInteger gitAsked = new AtomicInteger();

    private Supplier<GitState> git(GitState state) {
        return () -> {
            gitAsked.incrementAndGet();
            return state;
        };
    }

    @BeforeEach
    void resetGit() {
        gitAsked.set(0);
    }

    @Test
    void theRunGoesAheadWhenEveryConditionHolds() throws IOException {
        Files.writeString(results.resolve("dev-0123456789ab-20261015T120000Z.json"), "{}");   // dev results don't block

        assertThat(EvalGuards.checkHoldout(HOLDOUT, true, results, git(CLEAN))).isEqualTo(CLEAN);
        assertThat(gitAsked).hasValue(1);
    }

    @Test
    void aMissingResultsDirectoryIsNoEarlierRun() {
        assertThat(EvalGuards.checkHoldout(HOLDOUT, true, results.resolve("not-yet"), git(CLEAN))).isEqualTo(CLEAN);
    }

    /** The hash comes first: a changed file is reported even without confirmation, and git is never asked. */
    @Test
    void aChangedHoldoutIsRefusedBeforeAnythingElse(@TempDir Path dir) throws IOException {
        Path changed = dir.resolve("holdout.csv");
        Files.write(changed, Files.readAllBytes(HOLDOUT));
        Files.writeString(changed, "TAMPERED,-1.00,Dining,TAMPERED\n", StandardOpenOption.APPEND);
        Files.writeString(results.resolve("holdout-0123456789ab-20261015T120000Z.json"), "{}");

        assertThatThrownBy(() -> EvalGuards.checkHoldout(changed, false, results, git(new GitState("x", true))))
                .isInstanceOf(EvalRefusedException.class)
                .hasMessageContaining("SHA-256")
                .hasMessageContaining(EvalGuards.HOLDOUT_SHA_256);
        assertThat(gitAsked).hasValue(0);
    }

    @Test
    void anUnreadableHoldoutIsRefused(@TempDir Path dir) {
        assertThatThrownBy(() -> EvalGuards.checkHoldout(dir.resolve("missing.csv"), true, results, git(CLEAN)))
                .isInstanceOf(EvalRefusedException.class).hasMessageContaining("could not be read");
        assertThat(gitAsked).hasValue(0);
    }

    @Test
    void aRunWithoutConfirmationIsRefused() {
        assertThatThrownBy(() -> EvalGuards.checkHoldout(HOLDOUT, false, results, git(CLEAN)))
                .isInstanceOf(EvalRefusedException.class).hasMessageContaining("-Deval.holdout.confirm=true");
        assertThat(gitAsked).hasValue(0);
    }

    @Test
    void anEarlierHoldoutResultIsRefused() throws IOException {
        Files.writeString(results.resolve("holdout-0123456789ab-20261015T120000Z.md"), "# earlier");

        assertThatThrownBy(() -> EvalGuards.checkHoldout(HOLDOUT, true, results, git(CLEAN)))
                .isInstanceOf(EvalRefusedException.class)
                .hasMessageContaining("holdout-0123456789ab-20261015T120000Z.md")
                .hasMessageContaining("evaluated once");
        assertThat(gitAsked).hasValue(0);
    }

    @Test
    void aDirtyWorkingTreeIsRefused() {
        assertThatThrownBy(() -> EvalGuards.checkHoldout(HOLDOUT, true, results,
                git(new GitState(CLEAN.commit(), true))))
                .isInstanceOf(EvalRefusedException.class).hasMessageContaining("dirty");
    }

    @Test
    void theGuardPinsTheSameHashAsTheSetsTest() throws IOException {
        assertThat(EvalSet.sha256(Files.readAllBytes(HOLDOUT))).isEqualTo(EvalGuards.HOLDOUT_SHA_256);
        assertThat(Files.readString(Path.of("src/test/java/com/finalysis/categorize/CategorizationSetsTest.java")))
                .contains("\"" + EvalGuards.HOLDOUT_SHA_256 + "\"");
    }

    @Test
    void gitStateReadsThisRepository(@TempDir Path notARepository) {
        GitState state = GitState.read(ImportTestSupport.SAMPLE_DATA.getParent());

        assertThat(state.commit()).matches("[0-9a-f]{40}");
        assertThat(GitState.readOrUnknown(notARepository)).isEqualTo(GitState.UNKNOWN);
    }
}
