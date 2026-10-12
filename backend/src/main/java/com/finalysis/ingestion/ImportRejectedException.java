package com.finalysis.ingestion;

/** An import that was refused before anything was written. Mapped to a problem response. */
public class ImportRejectedException extends RuntimeException {

    private final ImportProblem problem;
    private final Long statementId;
    private final Integer lineNumber;

    public ImportRejectedException(ImportProblem problem, String detail) {
        this(problem, detail, null, null);
    }

    private ImportRejectedException(ImportProblem problem, String detail, Long statementId, Integer lineNumber) {
        super(detail);
        this.problem = problem;
        this.statementId = statementId;
        this.lineNumber = lineNumber;
    }

    static ImportRejectedException alreadyImported(long statementId) {
        return new ImportRejectedException(ImportProblem.ALREADY_IMPORTED,
                "This file was already imported as statement " + statementId, statementId, null);
    }

    static ImportRejectedException parseError(StatementParseException e) {
        // Parser messages carry line numbers and tag or column names only (StatementParseException).
        return new ImportRejectedException(ImportProblem.PARSE_ERROR, e.getMessage(), null, e.getLineNumber());
    }

    public ImportProblem getProblem() {
        return problem;
    }

    /** The existing statement, for {@link ImportProblem#ALREADY_IMPORTED}; otherwise null. */
    public Long getStatementId() {
        return statementId;
    }

    /** The failing line, for {@link ImportProblem#PARSE_ERROR} when known; otherwise null. */
    public Integer getLineNumber() {
        return lineNumber;
    }
}
