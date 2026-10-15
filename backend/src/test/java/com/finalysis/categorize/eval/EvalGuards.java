package com.finalysis.categorize.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * What must hold before the frozen holdout set is evaluated (CAT-05; docs/plans/phase-4.md §10, step 9a notes),
 * checked in this order, before any database, context or model is touched:
 * <ol>
 *   <li>holdout.csv still has its pinned SHA-256 (the same pin as {@code CategorizationSetsTest});</li>
 *   <li>the run was confirmed: {@code -D}{@value #CONFIRM_PROPERTY}{@code =true};</li>
 *   <li>no holdout results file exists in {@code docs/eval/}: the holdout is evaluated once;</li>
 *   <li>the working tree is clean, so the recorded commit is exactly the code that ran.</li>
 * </ol>
 */
final class EvalGuards {

    static final String HOLDOUT_SHA_256 = "2020780ea41e1c1916247250e5eaab847ab97a523635dae9a070ab6f6cee92af";
    static final String CONFIRM_PROPERTY = "eval.holdout.confirm";
    /** Results files are named {@code <set>-<commit>-<timestamp>.json} and {@code .md}. */
    static final String HOLDOUT_RESULTS_PREFIX = "holdout-";

    private EvalGuards() {
    }

    /** The guard said no; nothing was run. */
    static final class EvalRefusedException extends IllegalStateException {

        EvalRefusedException(String message) {
            super(message);
        }
    }

    /**
     * The commit being evaluated.
     *
     * @param dirty the working tree has changes or untracked files ({@code git status --porcelain} isn't empty)
     */
    record GitState(String commit, boolean dirty) {

        static final GitState UNKNOWN = new GitState("unknown", true);

        /** Reads HEAD and the working tree's status with git; fails when git can't say. */
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
            sha256 = EvalSet.sha256(Files.readAllBytes(holdout));
        } catch (IOException e) {
            throw new EvalRefusedException("holdout.csv could not be read: " + e.getClass().getSimpleName());
        }
        if (!HOLDOUT_SHA_256.equals(sha256)) {
            throw new EvalRefusedException("holdout.csv's SHA-256 is " + sha256 + ", not the pinned "
                    + HOLDOUT_SHA_256 + ". The holdout is frozen; restore it before evaluating.");
        }
        if (!confirmed) {
            throw new EvalRefusedException("The holdout is evaluated once, for reporting. Add -D" + CONFIRM_PROPERTY
                    + "=true to confirm.");
        }
        List<String> existing = existingResults(resultsDir);
        if (!existing.isEmpty()) {
            throw new EvalRefusedException("Holdout results already exist in " + resultsDir + ": " + existing
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
}
