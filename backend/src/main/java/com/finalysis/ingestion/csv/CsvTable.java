package com.finalysis.ingestion.csv;

/**
 * Where a delimited-text file's table is and how to read it, as detection found it
 * ({@link CsvStatementParser#detectTable}).
 *
 * @param encoding UTF-8 when the bytes are valid UTF-8, else windows-1252
 * @param skipRows physical lines before the table's first record (a preamble), counted as a
 *                 mapping's {@code skipRows} counts them
 */
public record CsvTable(char delimiter, String encoding, int skipRows) {
}
