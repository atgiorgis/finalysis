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
 * Checks a model's answer against the request it answers (docs/plans/phase-4.md §4.4). The model is never trusted:
 * <ul>
 *   <li>every key must be one the batch sent, answered exactly once, and every sent key must be answered;</li>
 *   <li>the category must be one of the allowed names (exact, after trimming);</li>
 *   <li>the confidence must be a number from 0 to 1.</li>
 * </ul>
 * One problem anywhere makes the whole answer invalid: a duplicate, missing or unknown key means the answers may no
 * longer line up with the items, so none of them is used. Pure Java; the answer's text is never kept or logged,
 * only the kinds of problem found.
 */
final class AnswerValidator {

    /** What can be wrong with an answer. Safe to log: no text from the model. */
    enum Problem {
        /** The output didn't parse into the answer structure. */
        UNPARSABLE,
        /** An answer for a key the batch didn't send. */
        UNKNOWN_KEY,
        /** A key answered more than once. */
        DUPLICATE_KEY,
        /** A sent key with no answer. */
        MISSING_KEY,
        /** A category that isn't one of the allowed names. */
        UNKNOWN_CATEGORY,
        /** A confidence that is missing or outside 0 to 1. */
        BAD_CONFIDENCE
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
     * @param problems empty when the answer is valid
     * @param byKey    one accepted answer per sent key when valid; empty otherwise
     */
    record Validation(Set<Problem> problems, Map<String, Accepted> byKey) {

        Validation {
            problems = Collections.unmodifiableSet(problems.isEmpty() ? EnumSet.noneOf(Problem.class)
                    : EnumSet.copyOf(problems));
            byKey = Collections.unmodifiableMap(new LinkedHashMap<>(byKey));
        }

        boolean valid() {
            return problems.isEmpty();
        }
    }

    /** The scale of {@code txn.category_confidence}. Rounding is formatting for storage, not a computed value. */
    static final int CONFIDENCE_SCALE = 3;

    private AnswerValidator() {
    }

    static Validation validate(CategorizationResponse response, Set<String> sentKeys, Set<String> allowedCategories) {
        if (response == null || response.answers() == null) {
            return new Validation(EnumSet.of(Problem.UNPARSABLE), Map.of());
        }
        Set<Problem> problems = EnumSet.noneOf(Problem.class);
        Map<String, Accepted> byKey = new LinkedHashMap<>();
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
            if (category == null || !allowedCategories.contains(category)) {
                problems.add(Problem.UNKNOWN_CATEGORY);
                continue;
            }
            BigDecimal confidence = confidence(answer.confidence());
            if (confidence == null) {
                problems.add(Problem.BAD_CONFIDENCE);
                continue;
            }
            byKey.put(answer.key(), new Accepted(category, confidence));
        }
        if (!seen.keySet().containsAll(sentKeys)) {
            problems.add(Problem.MISSING_KEY);
        }
        return problems.isEmpty() ? new Validation(problems, byKey) : new Validation(problems, Map.of());
    }

    /** The confidence at the stored scale, or null when it is missing, not a number, or outside 0 to 1. */
    private static BigDecimal confidence(Double value) {
        if (value == null || value.isNaN() || value.isInfinite() || value < 0 || value > 1) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP);
    }
}
