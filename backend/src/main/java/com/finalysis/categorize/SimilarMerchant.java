package com.finalysis.categorize;

/**
 * A merchant near a query embedding, with its trusted label (CAT-03).
 *
 * @param category   the category of its newest USER or RULE txn in the query's direction
 * @param similarity cosine similarity: 1 = same direction, 0 = unrelated, down to -1 = opposite
 */
public record SimilarMerchant(String merchant, String category, double similarity) {
}
