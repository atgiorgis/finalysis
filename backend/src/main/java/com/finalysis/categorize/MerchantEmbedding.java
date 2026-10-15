package com.finalysis.categorize;

/**
 * A stored merchant embedding (DAT-06) and the model that produced it.
 *
 * @param inputSha256 the SHA-256 (hex) of the redacted text that was embedded
 */
public record MerchantEmbedding(String merchant, String model, String inputSha256, float[] embedding) {
}
