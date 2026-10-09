package com.finalysis.categorize;

/** A stored merchant embedding (DAT-06) and the model that produced it. */
public record MerchantEmbedding(String merchant, float[] embedding, String model) {
}
