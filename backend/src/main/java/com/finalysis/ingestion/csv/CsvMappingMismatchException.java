package com.finalysis.ingestion.csv;

/**
 * The file's header (or column count, without a header row) doesn't match the signature the
 * account's mapping was saved with. Step 7 maps this to 422 {@code mapping-mismatch} with the
 * preview. Signatures are hashes or column counts ({@link CsvHeaderSignature}), never header text.
 */
public class CsvMappingMismatchException extends RuntimeException {

    private final String expectedSignature;
    private final String actualSignature;

    public CsvMappingMismatchException(String expectedSignature, String actualSignature) {
        super("The file's columns do not match the account's CSV mapping");
        this.expectedSignature = expectedSignature;
        this.actualSignature = actualSignature;
    }

    public String getExpectedSignature() {
        return expectedSignature;
    }

    public String getActualSignature() {
        return actualSignature;
    }
}
