package com.finalysis.chat.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * What must hold before the chat evaluation runs (docs/plans/phase-6.md §13.2, step 8), checked before any database,
 * context or model is touched. Phase 4's idiom ({@code categorize.eval.EvalGuards}), for the chat holdout
 * ({@code sample-data/chat/holdout.yml}), in this order:
 * <ol>
 *   <li>the file still has its pinned SHA-256 (the same pin as {@code ChatQuestionSetsTest});</li>
 *   <li>the run was confirmed: {@code -D}{@value #CONFIRM_PROPERTY}{@code =true};</li>
 *   <li>no chat holdout results exist in {@code docs/eval/}: it is evaluated once;</li>
 *   <li>the working tree is clean, so the recorded commit is exactly the code that ran.</li>
 * </ol>
 * Every run, dev or holdout, also needs a set and a model id the price file lists ({@link ChatEvalPrices}).
 */
final class ChatEvalGuards {

    static final String HOLDOUT_SHA256 = "25ee03caab9e7ac8b3df26c37367f8a5c7d2bf1a454ed509aa033e700b4a3ad8";
    static final String CONFIRM_PROPERTY = "eval.holdout.confirm";
    /** Step 8d: the turn cost limit for this run only, in dollars; the app's setting is unchanged. */
    static final String MAX_TURN_COST_PROPERTY = "eval.maxTurnCost";
    static final String DEV = "dev";
    static final String HOLDOUT = "holdout";
    /** Results are {@code chat-<set>-<commit12>-<timestamp>.json} and {@code .md}. */
    static final String HOLDOUT_RESULTS_PREFIX = "chat-" + HOLDOUT + "-";

    private ChatEvalGuards() {
    }

    /** A guard said no; nothing was run or sent. */
    static final class EvalRefusedException extends IllegalStateException {

        EvalRefusedException(String message) {
            super(message);
        }
    }

    /**
     * The commit being evaluated (a copy of Phase 4's, which is private to its package).
     *
     * @param dirty the working tree has changes or untracked files ({@code git status --porcelain} isn't empty)
     */
    record GitState(String commit, boolean dirty) {

        static final GitState UNKNOWN = new GitState("unknown", true);

        static GitState read(Path repository) {
            String commit = git(repository, "rev-parse", "HEAD").strip();
            boolean dirty = !git(repository, "status", "--porcelain").isBlank();
            return new GitState(commit, dirty);
        }

        /** {@link #read}, or {@link #UNKNOWN} when git can't say (dev runs only record it). */
        static GitState readOrUnknown(Path repository) {
            try {
                return read(repository);
            } catch (RuntimeException e) {
                return UNKNOWN;
            }
        }

        private static String git(Path repository, String... arguments) {
            List<String> command = Stream.concat(Stream.of("git", "-C", repository.toString()), Stream.of(arguments))
                    .toList();
            try {
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    throw new IllegalStateException("git " + String.join(" ", arguments) + " failed");
                }
                return output;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while running git", e);
            }
        }
    }

    /** The set to run: dev or holdout, nothing else. */
    static String set(String property) {
        if (!List.of(DEV, HOLDOUT).contains(property)) {
            throw new EvalRefusedException("Choose the set: -Deval.set=dev or -Deval.set=holdout");
        }
        return property;
    }

    /** The chat model to evaluate: given explicitly, and priced, so the run's cost limits and report hold. */
    static String model(String property, ChatEvalPrices prices) {
        if (property == null || property.isBlank()) {
            throw new EvalRefusedException("Choose the chat model: -Deval.model=<Bedrock model or inference-profile id>");
        }
        if (prices.chat(property).isEmpty()) {
            throw new EvalRefusedException("-Deval.model=" + property + " has no price in " + ChatEvalPrices.RESOURCE
                    + ". Add {\"inputPerMillionUsd\": ..., \"outputPerMillionUsd\": ...} under its exact id (from the "
                    + "Bedrock pricing page) before running it. Priced: " + prices.models().keySet());
        }
        return property;
    }

    /**
     * Refuses the holdout run unless every condition holds (see the class comment). {@code git} is asked last, so a
     * changed holdout is reported before anything else is looked at.
     *
     * @return the commit to record
     * @throws EvalRefusedException naming the first condition that failed
     */
    static GitState checkHoldout(Path holdout, boolean confirmed, Path resultsDir, Supplier<GitState> git) {
        String sha256;
        try {
            sha256 = ChatEvalSet.sha256(Files.readAllBytes(holdout));
        } catch (IOException e) {
            throw new EvalRefusedException("The chat holdout could not be read: " + e.getClass().getSimpleName());
        }
        if (!HOLDOUT_SHA256.equals(sha256)) {
            throw new EvalRefusedException("The chat holdout's SHA-256 is " + sha256 + ", not the pinned "
                    + HOLDOUT_SHA256 + ". It is frozen; restore it before evaluating.");
        }
        if (!confirmed) {
            throw new EvalRefusedException("The chat holdout is evaluated once, for reporting. Add -D"
                    + CONFIRM_PROPERTY + "=true to confirm.");
        }
        List<String> existing = existingResults(resultsDir);
        if (!existing.isEmpty()) {
            throw new EvalRefusedException("Chat holdout results already exist in " + resultsDir + ": " + existing
                    + ". The holdout is evaluated once.");
        }
        GitState state = git.get();
        if (state.dirty()) {
            throw new EvalRefusedException("The working tree is dirty. Commit or stash every change (untracked files "
                    + "too), so the recorded commit " + state.commit() + " is exactly what ran.");
        }
        return state;
    }

    private static List<String> existingResults(Path resultsDir) {
        if (!Files.isDirectory(resultsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(resultsDir)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.startsWith(HOLDOUT_RESULTS_PREFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code -Deval.maxTurnCost}: empty for the app's own setting, else dollars by the setting's own rules (at least 0,
     * at most 6 decimals; 0 turns the limit off). The context's startup check then refuses a model whose largest round
     * passes it, as it would in the app.
     */
    static BigDecimal maxTurnCost(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        BigDecimal dollars;
        try {
            dollars = new BigDecimal(value.strip());
        } catch (NumberFormatException e) {
            throw new EvalRefusedException("-D" + MAX_TURN_COST_PROPERTY + " must be dollars, such as 0.10: " + value);
        }
        if (dollars.signum() < 0 || dollars.scale() > 6) {
            throw new EvalRefusedException("-D" + MAX_TURN_COST_PROPERTY
                    + " must be at least 0 dollars, with at most 6 decimals: " + value);
        }
        return dollars;
    }
}
