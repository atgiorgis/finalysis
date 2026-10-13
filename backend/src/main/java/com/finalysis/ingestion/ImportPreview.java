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
 * and transaction count. A PDF no layout parser recognizes gets {@code format: "pdf"},
 * {@code recognized: false} and the reason it would be held as an unparsed upload (ING-13).
 *
 * @param format     the parser that would read the file, e.g. {@code csv} or {@code ofx}; {@code pdf}
 *                   for an unrecognized PDF
 * @param recognized false for an unrecognized PDF, otherwise absent
 * @param reason     why an unrecognized PDF would be held, otherwise absent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportPreview(String format, @JsonUnwrapped CsvPreview csv, LocalDate periodStart, LocalDate periodEnd,
                            Integer transactionCount, Boolean recognized, UnparsedReason reason) {

    static final String UNRECOGNIZED_PDF_FORMAT = "pdf";

    static ImportPreview csv(CsvPreview csv) {
        return new ImportPreview(CsvStatementParser.ID, csv, null, null, null, null, null);
    }

    static ImportPreview unrecognizedPdf(UnparsedReason reason) {
        return new ImportPreview(UNRECOGNIZED_PDF_FORMAT, null, null, null, null, false, reason);
    }

    /** The CSV preview without data rows, for error responses ({@link CsvPreview#withoutRows()}). */
    ImportPreview withoutRows() {
        return csv == null ? this : new ImportPreview(format, csv.withoutRows(), periodStart, periodEnd, transactionCount,
                recognized, reason);
    }

    static ImportPreview statement(String format, ParsedStatement parsed) {
        return new ImportPreview(format, null, parsed.periodStart(), parsed.periodEnd(), parsed.txns().size(), null,
                null);
    }

    /** No headers or rows: they come from the user's file (SEC-10). */
    @Override
    public String toString() {
        String content = csv != null ? ", " + csv : reason != null ? ", " + reason : ", " + transactionCount + " txns";
        return "ImportPreview[" + format + content + "]";
    }
}
