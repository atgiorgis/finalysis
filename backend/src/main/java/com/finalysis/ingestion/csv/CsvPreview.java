package com.finalysis.ingestion.csv;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finalysis.account.AccountType;
import com.finalysis.ingestion.ImportFile;
import com.finalysis.ingestion.StatementParseException;
import com.finalysis.ingestion.csv.CsvStatementParser.RawRecord;
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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CsvPreview(String delimiter, String encoding, int skipRows, List<String> headers, List<List<String>> rows,
                         MappingStatus mappingStatus, SuggestedCsvMapping suggestedMapping) {

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
        return new CsvPreview(delimiter, encoding, skipRows, headers, null, mappingStatus, suggestedMapping);
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
                rows.subList(0, Math.min(ROWS, rows.size())), status, suggestion);
    }

    /** Counts only: headers and rows come from the user's file (SEC-10). */
    @Override
    public String toString() {
        return "CsvPreview[" + headers.size() + " columns, " + (rows == null ? 0 : rows.size()) + " rows, "
                + mappingStatus + "]";
    }
}
