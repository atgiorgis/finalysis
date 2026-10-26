package com.finalysis.ingestion.csv;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finalysis.account.AccountType;
import com.finalysis.api.ApiMoney;
import com.finalysis.ingestion.ImportFile;
import com.finalysis.ingestion.StatementParseException;
import com.finalysis.ingestion.csv.CsvStatementParser.RawRecord;
import java.math.BigDecimal;
import java.util.List;

/**
 * How the importer reads a CSV file, so the user can confirm a mapping before importing (ING-01).
 * It holds the file's headers and first rows: it goes back to the uploader only and is never
 * logged (SEC-10). Inside an error response it carries no rows ({@link #withoutRows()}).
 *
 * @param skipRows         where detection found the table (a suggested {@code skipRows})
 * @param headers          the first record of the table, each cell stripped
 * @param rows             the first {@value #ROWS} data rows, cells as read; null without rows
 * @param suggestedMapping present unless the saved mapping matches the file
 * @param signExample      when the suggested sign is a guess (step 7e): one row read both ways, so the user says
 *                         which is right; otherwise absent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CsvPreview(String delimiter, String encoding, int skipRows, List<String> headers, List<List<String>> rows,
                         MappingStatus mappingStatus, SuggestedCsvMapping suggestedMapping, SignExample signExample) {

    /**
     * The first data row with a non-zero amount, read as the importer would read it with each sign (step 7e). The
     * amount and directions are worked out here, so the page parses nothing.
     *
     * @param row         its number among the data rows, from 1
     * @param description the suggested description column's cell, as read (shown to the uploader only)
     * @param amount      the amount without its sign
     * @param asIs        its direction when the file is read {@link CsvSign#AS_IS} (purchases negative)
     * @param inverted    its direction when the file is read {@link CsvSign#INVERTED} (purchases positive)
     */
    public record SignExample(int row, String description, @ApiMoney BigDecimal amount, Direction asIs,
                              Direction inverted) {

        /** Never a description: it comes from the user's file (SEC-10). */
        @Override
        public String toString() {
            return "SignExample[row " + row + ", " + asIs + "/" + inverted + "]";
        }
    }

    /** Which way money moves under the sign convention (negative = money out). */
    public enum Direction {
        MONEY_OUT,
        MONEY_IN
    }

    public static final int ROWS = 10;

    /** How the account's saved mapping relates to this file. */
    public enum MappingStatus {
        /** The account has no mapping. */
        NONE,
        /** The saved mapping reads this file's header (or column count). */
        MATCHES,
        /** The saved mapping was saved for other headers; importing would be a mapping mismatch. */
        MISMATCH
    }

    public CsvPreview {
        headers = List.copyOf(headers);
        rows = rows == null ? null : rows.stream().map(List::copyOf).toList();
    }

    /**
     * The same preview without data rows, for the 422 mapping-mismatch response: error responses
     * carry no file contents beyond the headers (SEC-10). The preview endpoint shows the rows.
     */
    public CsvPreview withoutRows() {
        return new CsvPreview(delimiter, encoding, skipRows, headers, null, mappingStatus, suggestedMapping, null);
    }

    /**
     * Previews a file the CSV parser detected.
     *
     * @param saved the account's mapping, or null when it has none
     * @throws StatementParseException if the file can't be read as the detected table
     */
    public static CsvPreview of(ImportFile file, AccountType accountType, CsvMappingSpec saved) {
        CsvTable table = CsvStatementParser.detectTable(file.bytes())
                .orElseThrow(() -> new StatementParseException(null, "the file is not a delimited table"));
        List<RawRecord> records = CsvStatementParser.tableRecords(file.bytes(), table);
        List<String> headers = records.getFirst().values().stream().map(String::strip).toList();
        List<List<String>> rows = records.subList(1, records.size()).stream().map(RawRecord::values).toList();

        MappingStatus status;
        if (saved == null) {
            status = MappingStatus.NONE;
        } else if (saved.values().headerSignature().equals(CsvStatementParser.headerSignature(file.bytes(), saved.values()))) {
            status = MappingStatus.MATCHES;
        } else {
            status = MappingStatus.MISMATCH;
        }
        SuggestedCsvMapping suggestion = status == MappingStatus.MATCHES
                ? null
                : CsvMappingSuggester.suggest(headers, rows, table, accountType);
        return new CsvPreview(String.valueOf(table.delimiter()), table.encoding(), table.skipRows(), headers,
                rows.subList(0, Math.min(ROWS, rows.size())), status, suggestion, signExample(headers, rows, suggestion));
    }

    /** Only for a guessed sign on a single amount column; null when no row has an amount that reads as one. */
    static SignExample signExample(List<String> headers, List<List<String>> rows, SuggestedCsvMapping suggestion) {
        if (suggestion == null || suggestion.confidence().get("sign") != SuggestedCsvMapping.Confidence.GUESSED) {
            return null;
        }
        CsvMappingRequest mapping = suggestion.mapping();
        if (mapping.amountMode() != CsvAmountMode.SINGLE || mapping.amountColumn() == null) {
            return null;
        }
        int amountIndex = headers.indexOf(mapping.amountColumn());
        int descriptionIndex = mapping.descriptionColumns().isEmpty() ? -1
                : headers.indexOf(mapping.descriptionColumns().getFirst());
        if (amountIndex < 0) {
            return null;
        }
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (amountIndex >= row.size()) {
                continue;
            }
            BigDecimal amount;
            try {
                amount = CsvAmount.parse(row.get(amountIndex));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (amount.signum() == 0) {
                continue;
            }
            String description = descriptionIndex >= 0 && descriptionIndex < row.size() ? row.get(descriptionIndex)
                    : null;
            Direction asIs = amount.signum() < 0 ? Direction.MONEY_OUT : Direction.MONEY_IN;
            Direction inverted = asIs == Direction.MONEY_OUT ? Direction.MONEY_IN : Direction.MONEY_OUT;
            return new SignExample(i + 1, description, amount.abs(), asIs, inverted);
        }
        return null;
    }

    /** Counts only: headers and rows come from the user's file (SEC-10). */
    @Override
    public String toString() {
        return "CsvPreview[" + headers.size() + " columns, " + (rows == null ? 0 : rows.size()) + " rows, "
                + mappingStatus + "]";
    }
}
