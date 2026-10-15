package com.finalysis.categorize;

import java.util.List;

/**
 * What the model must return (docs/plans/phase-4.md §4.2, as changed in step 7): structured output, checked in Java
 * by {@link AnswerValidator} before anything is applied (§4.4). Nothing in it is trusted: keys, categories and
 * confidences are all validated against the request.
 */
public record CategorizationResponse(List<Answer> answers) {

    /**
     * @param key        the request item's key ({@code i1}…)
     * @param category   one of the request's allowed category names
     * @param confidence the model's confidence that the category is right, from 0 to 1; answers below
     *                   {@code finalysis.ai.categorization.min-confidence} are not applied
     */
    public record Answer(String key, String category, Double confidence) {
    }
}
