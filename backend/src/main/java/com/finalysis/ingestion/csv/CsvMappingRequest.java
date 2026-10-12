package com.finalysis.ingestion.csv;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of {@code PUT /api/accounts/{id}/csv-mapping}. Strings are stripped before validation.
 *
 * <p>{@code headers} are the file's header names from the preview, required when {@code headerRow}
 * is true (the default); the server checks every column against them and computes the header
 * signature. Without a header row, columns are zero-based indexes ({@code "0"}) checked against
 * {@code columnCount} instead. Cross-field rules are checked by {@link CsvMappingService}.
 */
public record CsvMappingRequest(
        @Size(max = MAX_COLUMNS) List<@NotNull @Size(max = MAX_NAME) String> headers,
        @Min(1) @Max(MAX_COLUMNS) Integer columnCount,
        String delimiter,
        String encoding,
        Boolean headerRow,
        @Min(0) @Max(MAX_SKIP_ROWS) Integer skipRows,
        @NotBlank @Size(max = MAX_NAME) String dateColumn,
        @NotBlank @Size(max = CsvDateFormat.MAX_LENGTH) String dateFormat,
        @Size(max = MAX_NAME) String postDateColumn,
        @NotEmpty @Size(max = MAX_DESCRIPTION_COLUMNS)
        List<@NotBlank @Size(max = MAX_NAME) String> descriptionColumns,
        @NotNull CsvAmountMode amountMode,
        @Size(max = MAX_NAME) String amountColumn,
        @Size(max = MAX_NAME) String debitColumn,
        @Size(max = MAX_NAME) String creditColumn,
        CsvSign sign,
        @Size(max = MAX_NAME) String balanceColumn,
        @Size(max = MAX_NAME) String checkNumberColumn) {

    // Mirror ck_csv_mapping_column_length, ck_csv_mapping_description_columns, ck_csv_mapping_skip_rows
    // and the three-digit indexes of ck_csv_mapping_column_indexes (V6).
    public static final int MAX_NAME = 255;
    public static final int MAX_COLUMNS = 999;
    public static final int MAX_DESCRIPTION_COLUMNS = 10;
    public static final int MAX_SKIP_ROWS = 100;

    public CsvMappingRequest {
        headers = stripAll(headers);
        dateColumn = strip(dateColumn);
        dateFormat = strip(dateFormat);
        postDateColumn = strip(postDateColumn);
        descriptionColumns = stripAll(descriptionColumns);
        amountColumn = strip(amountColumn);
        debitColumn = strip(debitColumn);
        creditColumn = strip(creditColumn);
        balanceColumn = strip(balanceColumn);
        checkNumberColumn = strip(checkNumberColumn);
        // delimiter and encoding are not stripped: a tab or a space is a valid delimiter.
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static List<String> stripAll(List<String> values) {
        // Not List.copyOf: null elements must survive to be reported by @NotNull/@NotBlank.
        return values == null ? null : values.stream().map(CsvMappingRequest::strip).toList();
    }
}
