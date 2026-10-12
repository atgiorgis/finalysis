package com.finalysis.ingestion.csv;

/** Values of ck_csv_mapping_amount_mode; names must match the CHECK constraint exactly. */
public enum CsvAmountMode {
    /** One signed amount column. */
    SINGLE,
    /** Separate unsigned columns for money out (debit) and money in (credit). */
    DEBIT_CREDIT
}
