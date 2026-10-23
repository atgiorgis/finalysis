package com.finalysis.chat.tool;

import java.util.List;
import java.util.Map;

/**
 * A {@code search_transactions} query as the scope prepared it (docs/plans/phase-6.md §4.3): the text that was allowed
 * to leave (redacted like the question and checked by the merchant rules), its words for the name match, its vector
 * when the embedding model gave one, and the turn's directory of nameable merchants. Never sent back to the model.
 *
 * @param vector        null when no embedding model is configured or the call failed: the search is by name only
 * @param model         the embedding model the vector came from; only vectors of this model are compared
 * @param minSimilarity the cosine similarity a merchant or a category needs to be found by meaning
 * @param categories    the data's category names → ids, for matching categories (step 8b)
 * @param matchWords    the words that may pick a merchant alone in the word match: generic words and single
 *                      characters left out (step 8e)
 */
public record SearchText(String outbound, List<String> words, float[] vector, String model, double minSimilarity,
                         MerchantDirectory merchants, Map<String, Long> categories, List<String> matchWords) {

    public SearchText {
        words = List.copyOf(words);
        categories = Map.copyOf(categories);
        matchWords = List.copyOf(matchWords);
    }

    public SearchText(String outbound, List<String> words, float[] vector, String model, double minSimilarity,
                      MerchantDirectory merchants) {
        this(outbound, words, vector, model, minSimilarity, merchants, Map.of(), words);
    }

    public boolean byMeaning() {
        return vector != null;
    }
}
