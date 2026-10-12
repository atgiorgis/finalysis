package com.finalysis.ingestion.csv;

import java.util.List;

/**
 * A complete, validated mapping as {@link CsvMappingService} builds it from a request: defaults
 * applied, names stripped, every column checked against the file's headers or column count, and
 * the header signature computed. Nullable fields are optional columns. Immutable.
 */
public record CsvMappingValues(
        String delimiter,
        String encoding,
        boolean headerRow,
        int skipRows,
        String headerSignature,
        String dateColumn,
        String dateFormat,
        String postDateColumn,
        List<String> descriptionColumns,
        CsvAmountMode amountMode,
        String amountColumn,
        String debitColumn,
        String creditColumn,
        CsvSign sign,
        String balanceColumn,
        String checkNumberColumn) {

    public CsvMappingValues {
        descriptionColumns = List.copyOf(descriptionColumns);
    }

    /** The column whose value is the printed date in fingerprints: the post date when mapped. */
    public String printedDateColumn() {
        return postDateColumn != null ? postDateColumn : dateColumn;
    }
}
