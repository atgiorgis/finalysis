package com.finalysis.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.chat.eval.ChatEvalGuards.EvalRefusedException;
import com.finalysis.chat.eval.ChatEvalGuards.GitState;
import com.finalysis.support.ImportTestSupport;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The chat evaluation's guards (docs/plans/phase-6.md §13.2, step 8). Reads nothing of the chat holdout but its hash:
 * the guards' own conditions are what is under test, each one failing alone, in the order they are checked.
 */
class ChatEvalGuardsTest {

    private static final Path HOLDOUT = ImportTestSupport.SAMPLE_DATA.resolve("chat/holdout.yml");
    private static final GitState CLEAN = new GitState("0123456789abcdef0123456789abcdef01234567", false);
    private static final ChatEvalPrices PRICES = new ChatEvalPrices("test", null, Map.of(
            "priced-chat-model", new ChatEvalPrices.Price(new BigDecimal("1.00"), new BigDecimal("5.00"), null, null),
            "an-embedding-model", new ChatEvalPrices.Price(new BigDecimal("0.02"), BigDecimal.ZERO, null, null)));

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
        Files.writeString(results.resolve("chat-dev-0123456789ab-20261015T120000Z.json"), "{}");
        Files.writeString(results.resolve("chat-sweep-dev-0123456789ab-20261015T120000Z.md"), "#");
        Files.writeString(results.resolve("holdout-0123456789ab-20261015T120000Z.md"), "# not a chat result");

        assertThat(ChatEvalGuards.checkHoldout(HOLDOUT, true, results, git(CLEAN))).isEqualTo(CLEAN);
        assertThat(gitAsked).hasValue(1);
    }

    @Test
    void aMissingResultsDirectoryIsNoEarlierRun() {
        assertThat(ChatEvalGuards.checkHoldout(HOLDOUT, true, results.resolve("not-yet"), git(CLEAN)))
                .isEqualTo(CLEAN);
    }

    /** The hash comes first: a changed file is reported even without confirmation, and git is never asked. */
    @Test
    void aChangedHoldoutIsRefusedBeforeAnythingElse(@TempDir Path dir) throws IOException {
        Path changed = dir.resolve("holdout.yml");
        Files.write(changed, Files.readAllBytes(HOLDOUT));
        Files.writeString(changed, "# tampered\n", StandardOpenOption.APPEND);
        Files.writeString(results.resolve("chat-holdout-0123456789ab-20261015T120000Z.json"), "{}");

        assertThatThrownBy(() -> ChatEvalGuards.checkHoldout(changed, false, results, git(new GitState("x", true))))
                .isInstanceOf(EvalRefusedException.class)
                .hasMessageContaining("SHA-256")
                .hasMessageContaining(ChatEvalGuards.HOLDOUT_SHA256);
        assertThat(gitAsked).hasValue(0);
    }

    @Test
    void anUnreadableHoldoutIsRefused(@TempDir Path dir) {
        assertThatThrownBy(() -> ChatEvalGuards.checkHoldout(dir.resolve("missing.yml"), true, results, git(CLEAN)))
                .isInstanceOf(EvalRefusedException.class).hasMessageContaining("could not be read");
        assertThat(gitAsked).hasValue(0);
    }

    @Test
    void aRunWithoutConfirmationIsRefused() {
        assertThatThrownBy(() -> ChatEvalGuards.checkHoldout(HOLDOUT, false, results, git(CLEAN)))
                .isInstanceOf(EvalRefusedException.class).hasMessageContaining("-Deval.holdout.confirm=true");
        assertThat(gitAsked).hasValue(0);
    }

    @Test
    void anEarlierChatHoldoutResultIsRefused() throws IOException {
        Files.writeString(results.resolve("chat-holdout-0123456789ab-20261015T120000Z.md"), "# earlier");

        assertThatThrownBy(() -> ChatEvalGuards.checkHoldout(HOLDOUT, true, results, git(CLEAN)))
                .isInstanceOf(EvalRefusedException.class)
                .hasMessageContaining("chat-holdout-0123456789ab-20261015T120000Z.md")
                .hasMessageContaining("evaluated once");
        assertThat(gitAsked).hasValue(0);
    }

    @Test
    void aDirtyWorkingTreeIsRefused() {
        assertThatThrownBy(() -> ChatEvalGuards.checkHoldout(HOLDOUT, true, results,
                git(new GitState(CLEAN.commit(), true))))
                .isInstanceOf(EvalRefusedException.class).hasMessageContaining("dirty");
    }

    @Test
    void theGuardPinsTheSameHashAsTheQuestionSetsTest() throws IOException {
        assertThat(ChatEvalSet.sha256(Files.readAllBytes(HOLDOUT))).isEqualTo(ChatEvalGuards.HOLDOUT_SHA256);
        assertThat(Files.readString(Path.of("src/test/java/com/finalysis/chat/ChatQuestionSetsTest.java")))
                .contains("\"" + ChatEvalGuards.HOLDOUT_SHA256 + "\"");
    }

    @Test
    void theResultsPrefixIsWhatTheReportNamesAHoldoutRun() {
        assertThat(ChatEvalReport.PREFIX + ChatEvalGuards.HOLDOUT + "-").isEqualTo(ChatEvalGuards.HOLDOUT_RESULTS_PREFIX);
    }

    @Test
    void onlyDevOrHoldoutIsASet() {
        assertThat(ChatEvalGuards.set("dev")).isEqualTo("dev");
        assertThat(ChatEvalGuards.set("holdout")).isEqualTo("holdout");
        for (String bad : new String[] {"", "Dev", "holdout.yml", "all"}) {
            assertThatThrownBy(() -> ChatEvalGuards.set(bad)).isInstanceOf(EvalRefusedException.class)
                    .hasMessageContaining("-Deval.set=dev");
        }
    }

    @Test
    void theModelMustBeGivenAndPricedAsAChatModel() {
        assertThat(ChatEvalGuards.model("priced-chat-model", PRICES)).isEqualTo("priced-chat-model");
        assertThatThrownBy(() -> ChatEvalGuards.model("", PRICES)).isInstanceOf(EvalRefusedException.class)
                .hasMessageContaining("-Deval.model=");
        assertThatThrownBy(() -> ChatEvalGuards.model("us.anthropic.unpriced-v1:0", PRICES))
                .isInstanceOf(EvalRefusedException.class)
                .hasMessageContaining("no price in " + ChatEvalPrices.RESOURCE)
                .hasMessageContaining("priced-chat-model");
        assertThatThrownBy(() -> ChatEvalGuards.model("an-embedding-model", PRICES))
                .as("an embedding price is not a chat price").isInstanceOf(EvalRefusedException.class);
    }

    @Test
    void thePriceFileListsTheMainModelOfTheAwsProfile() {
        ChatEvalPrices prices = ChatEvalPrices.load();

        assertThat(prices.chat("us.anthropic.claude-haiku-4-5-20251001-v1:0")).isPresent();
        assertThat(prices.chat("amazon.titan-embed-text-v2:0")).isEmpty();
        assertThat(prices.source()).contains("-Deval.model");
    }

    @Test
    void gitStateReadsThisRepository(@TempDir Path notARepository) {
        GitState state = GitState.read(ImportTestSupport.SAMPLE_DATA.getParent());

        assertThat(state.commit()).matches("[0-9a-f]{40}");
        assertThat(GitState.readOrUnknown(notARepository)).isEqualTo(GitState.UNKNOWN);
    }

    /** Step 8d: {@code -Deval.maxTurnCost} follows the setting's own rules; empty keeps the app's. */
    @Test
    void theTurnCostOverrideIsDollarsByTheSettingsRules() {
        assertThat(ChatEvalGuards.maxTurnCost(null)).isNull();
        assertThat(ChatEvalGuards.maxTurnCost(" ")).isNull();
        assertThat(ChatEvalGuards.maxTurnCost("0.10")).isEqualByComparingTo("0.10");
        assertThat(ChatEvalGuards.maxTurnCost("0")).isEqualByComparingTo("0");
        for (String bad : List.of("-0.01", "0.0000001", "ten cents")) {
            assertThatThrownBy(() -> ChatEvalGuards.maxTurnCost(bad))
                    .isInstanceOf(EvalRefusedException.class)
                    .hasMessageContaining("-Deval.maxTurnCost");
        }
    }
}
