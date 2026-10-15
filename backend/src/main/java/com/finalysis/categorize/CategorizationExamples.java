package com.finalysis.categorize;

import java.util.List;

/**
 * The {@code <examples>} block of a categorization call (CAT-03; docs/plans/phase-4.md §6, step 8 notes): merchants
 * similar to the batch's items that this user's own rules or corrections already categorized, as data. Merchant
 * text and category only, redacted and guard-checked exactly like an item's merchant; no key, amount or link to an
 * item. Built only by {@link CategorizationPromptBuilder}.
 *
 * @param examples distinct, sorted by merchant then category
 */
public record CategorizationExamples(List<Example> examples) {

    public CategorizationExamples {
        examples = List.copyOf(examples);
    }

    /** A similar merchant of this user's and its category; the merchant is redacted like an item's. */
    public record Example(String merchant, String category) {
    }
}
