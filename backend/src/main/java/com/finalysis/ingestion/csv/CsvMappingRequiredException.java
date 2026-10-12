package com.finalysis.ingestion.csv;

/**
 * A CSV file was uploaded for an account without a saved mapping. Step 7 maps this to 422
 * {@code mapping-required}, pointing the client to the preview.
 */
public class CsvMappingRequiredException extends RuntimeException {

    public CsvMappingRequiredException() {
        super("The account has no CSV mapping; save one before importing CSV files");
    }
}
