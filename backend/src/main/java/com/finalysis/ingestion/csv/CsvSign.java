package com.finalysis.ingestion.csv;

/**
 * How the amount read from the file maps to the app's convention (negative = money out). Values of
 * ck_csv_mapping_sign; names must match the CHECK constraint exactly.
 */
public enum CsvSign {
    /** The file already follows the convention. */
    AS_IS,
    /** The file prints money out as positive (common on card exports), so every amount is negated. */
    INVERTED
}
