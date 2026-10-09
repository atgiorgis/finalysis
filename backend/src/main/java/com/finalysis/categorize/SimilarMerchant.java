package com.finalysis.categorize;

/** A merchant and its cosine distance from a query embedding: 0 = same direction, up to 2 = opposite. */
public record SimilarMerchant(String merchant, double distance) {
}
