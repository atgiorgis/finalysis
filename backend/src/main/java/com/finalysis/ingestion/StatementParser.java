package com.finalysis.ingestion;

/**
 * Reads one statement format. New formats are new implementations; existing ones are not
 * modified to support them (GR-05, ING-12).
 *
 * <p>Implementations are Spring beans, collected by {@link StatementParserRegistry}. They must
 * be stateless and must not log file contents (SEC-10).
 */
public interface StatementParser {

    /** Short stable name, e.g. {@code ofx}; stored with each imported statement. */
    String id();

    /**
     * Judges the file by its content, never by its name or the client's content type. Must not
     * throw for files in other formats; return {@link FormatMatch#NONE} instead.
     */
    FormatMatch detect(ImportFile file);

    /**
     * Reads the whole file. Descriptions are passed out exactly as extracted (see
     * {@link ParsedTxn#rawDescription()}).
     *
     * @throws StatementParseException if the file is in this format but can't be read
     */
    ParsedStatement parse(ImportFile file, ParseContext context);
}
