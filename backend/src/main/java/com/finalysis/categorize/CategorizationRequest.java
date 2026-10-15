package com.finalysis.categorize;

import java.util.List;

/**
 * The items a categorization call sends, in the {@code <transactions>} block (docs/plans/phase-4.md §4.2). Built
 * only by {@link CategorizationPromptBuilder}, so every merchant in it went through the {@link Redactor} and
 * {@link OutboundPayloadGuard}. Nothing else is ever sent: no description, date, account, counterparty or id.
 * Examples are not here: they are a separate block ({@link CategorizationExamples}).
 *
 * @param allowedCategories every category name, sorted; the model must answer with one of them exactly
 */
public record CategorizationRequest(List<String> allowedCategories, List<Item> items) {

    public CategorizationRequest {
        allowedCategories = List.copyOf(allowedCategories);
        items = List.copyOf(items);
    }

    /**
     * One merchant and direction.
     *
     * @param key      per request ({@code i1}, {@code i2}…), never a txn or merchant id
     * @param merchant the redacted cleaned merchant
     * @param amount   the most recent txn's amount as stored, e.g. {@code -25.67}
     */
    public record Item(String key, String merchant, String amount) {
    }
}
