package com.finalysis.categorize;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Checks a model's answer against the request it answers (docs/plans/phase-4.md §4.4, step 8 notes). The model is
 * never trusted. Two levels:
 * <ul>
 *   <li><b>The whole answer</b>: it must parse, every key must be one the batch sent, answered exactly once, and
 *       every sent key must be answered. One problem here makes the whole answer invalid ({@link Validation#valid()}
 *       false, nothing used, and the caller asks once more): a duplicate, missing or unknown key means the answers
 *       may no longer line up with the items.</li>
 *   <li><b>One item</b>: the category must be one of the allowed names (exact, after trimming) and the confidence a
 *       number from 0 to 1. A problem here rejects only that item ({@link Validation#rejected()}); the others are
 *       accepted.</li>
 * </ul>
 * Pure Java; the answer's text is never kept or logged, only the kinds of problem found.
 */
final class AnswerValidator {

    /** What can be wrong with an answer. Safe to log: no text from the model. */
    enum Problem {
        /** The output didn't parse into the answer structure. Rejects the whole answer. */
        UNPARSABLE(true),
        /** An answer for a key the batch didn't send. Rejects the whole answer. */
        UNKNOWN_KEY(true),
        /** A key answered more than once. Rejects the whole answer. */
        DUPLICATE_KEY(true),
        /** A sent key with no answer. Rejects the whole answer. */
        MISSING_KEY(true),
        /** A category that isn't one of the allowed names. Rejects that item only. */
        UNKNOWN_CATEGORY(false),
        /** A confidence that is missing or outside 0 to 1. Rejects that item only. */
        BAD_CONFIDENCE(false);

        private final boolean wholeAnswer;

        Problem(boolean wholeAnswer) {
            this.wholeAnswer = wholeAnswer;
        }

        boolean wholeAnswer() {
            return wholeAnswer;
        }
    }

    /**
     * An answer that passed every check.
     *
     * @param category   an allowed name, exactly as listed
     * @param confidence the model's confidence at the stored scale (three decimals)
     */
    record Accepted(String category, BigDecimal confidence) {
    }

    /**
     * @param problems problems with the whole answer; empty when it is usable
     * @param byKey    the accepted answer per key when usable; empty otherwise
     * @param rejected the item problem per key when usable (each sent key is in {@code byKey} or here); empty
     *                 otherwise
     */
    record Validation(Set<Problem> problems, Map<String, Accepted> byKey, Map<String, Problem> rejected) {

        Validation {
            problems = Collections.unmodifiableSet(problems.isEmpty() ? EnumSet.noneOf(Problem.class)
                    : EnumSet.copyOf(problems));
            byKey = Collections.unmodifiableMap(new LinkedHashMap<>(byKey));
            rejected = Collections.unmodifiableMap(new LinkedHashMap<>(rejected));
        }

        /** Whether the answer as a whole is usable; some of its items may still be rejected. */
        boolean valid() {
            return problems.isEmpty();
        }

        /** The kinds of item problem found; safe to log. */
        Set<Problem> itemProblems() {
            return rejected.isEmpty() ? EnumSet.noneOf(Problem.class) : EnumSet.copyOf(rejected.values());
        }
    }

    /** The scale of {@code txn.category_confidence}. Rounding is formatting for storage, not a computed value. */
    static final int CONFIDENCE_SCALE = 3;

    private AnswerValidator() {
    }

    static Validation validate(CategorizationResponse response, Set<String> sentKeys, Set<String> allowedCategories) {
        if (response == null || response.answers() == null) {
            return invalid(EnumSet.of(Problem.UNPARSABLE));
        }
        Set<Problem> problems = EnumSet.noneOf(Problem.class);
        Map<String, Accepted> byKey = new LinkedHashMap<>();
        Map<String, Problem> rejected = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (CategorizationResponse.Answer answer : response.answers()) {
            if (answer == null || answer.key() == null || !sentKeys.contains(answer.key())) {
                problems.add(Problem.UNKNOWN_KEY);
                continue;
            }
            if (seen.merge(answer.key(), 1, Integer::sum) > 1) {
                problems.add(Problem.DUPLICATE_KEY);
                continue;
            }
            String category = answer.category() == null ? null : answer.category().strip();
            BigDecimal confidence = confidence(answer.confidence());
            if (category == null || !allowedCategories.contains(category)) {
                rejected.put(answer.key(), Problem.UNKNOWN_CATEGORY);
            } else if (confidence == null) {
                rejected.put(answer.key(), Problem.BAD_CONFIDENCE);
            } else {
                byKey.put(answer.key(), new Accepted(category, confidence));
            }
        }
        if (!seen.keySet().containsAll(sentKeys)) {
            problems.add(Problem.MISSING_KEY);
        }
        return problems.isEmpty() ? new Validation(problems, byKey, rejected) : invalid(problems);
    }

    private static Validation invalid(Set<Problem> problems) {
        return new Validation(problems, Map.of(), Map.of());
    }

    /** The confidence at the stored scale, or null when it is missing, not a number, or outside 0 to 1. */
    private static BigDecimal confidence(Double value) {
        if (value == null || value.isNaN() || value.isInfinite() || value < 0 || value > 1) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP);
    }
}
