package com.finalysis.categorize;

import java.util.List;

/**
 * What the model must return (docs/plans/phase-4.md §4.2): structured output, checked in Java before anything
 * is applied (§4.4).
 */
public record CategorizationResponse(List<Answer> answers) {

    public record Answer(String key, String category, Confidence confidence) {
    }

    /** HIGH and MEDIUM are applied; LOW is not (§15, decision 3). */
    public enum Confidence {
        HIGH, MEDIUM, LOW
    }
}
