package com.finalysis.ingestion;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.finalysis.ingestion.csv.CsvPreview;
import com.finalysis.ingestion.csv.CsvStatementParser;
import java.time.LocalDate;

/**
 * What an import of a file would read, without writing anything ({@code POST
 * /api/accounts/{id}/imports/preview}). CSV files get the detected layout, the first rows and the
 * mapping status ({@link CsvPreview}, unwrapped into this object); other formats get their period
 * and transaction count.
 *
 * @param format the parser that would read the file, e.g. {@code csv} or {@code ofx}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportPreview(String format, @JsonUnwrapped CsvPreview csv, LocalDate periodStart, LocalDate periodEnd,
                            Integer transactionCount) {

    static ImportPreview csv(CsvPreview csv) {
        return new ImportPreview(CsvStatementParser.ID, csv, null, null, null);
    }

    /** The CSV preview without data rows, for error responses ({@link CsvPreview#withoutRows()}). */
    ImportPreview withoutRows() {
        return csv == null ? this : new ImportPreview(format, csv.withoutRows(), periodStart, periodEnd, transactionCount);
    }

    static ImportPreview statement(String format, ParsedStatement parsed) {
        return new ImportPreview(format, null, parsed.periodStart(), parsed.periodEnd(), parsed.txns().size());
    }

    /** No headers or rows: they come from the user's file (SEC-10). */
    @Override
    public String toString() {
        return "ImportPreview[" + format + (csv != null ? ", " + csv : ", " + transactionCount + " txns") + "]";
    }
}
