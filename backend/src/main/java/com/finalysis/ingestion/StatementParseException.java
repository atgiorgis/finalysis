package com.finalysis.ingestion;

/**
 * A file that a parser recognized but could not read. The whole import fails: a partial import
 * would silently make totals wrong.
 *
 * <p>The message must never contain file contents, only what is wrong and where (SEC-10), e.g.
 * "invalid date in column 'Date'".
 */
public class StatementParseException extends RuntimeException {

    private final Integer lineNumber;

    /** @param lineNumber 1-based line in the file, or null when the problem isn't tied to a line */
    public StatementParseException(Integer lineNumber, String message) {
        super(lineNumber == null ? message : "line " + lineNumber + ": " + message);
        this.lineNumber = lineNumber;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }
}
