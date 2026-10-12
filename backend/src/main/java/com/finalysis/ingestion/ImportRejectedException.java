package com.finalysis.ingestion;

/** An import that was refused before anything was written. Mapped to a problem response. */
public class ImportRejectedException extends RuntimeException {

    private final ImportProblem problem;
    private final Long statementId;
    private final Integer lineNumber;
    private final ImportPreview preview;

    public ImportRejectedException(ImportProblem problem, String detail) {
        this(problem, detail, null, null, null);
    }

    private ImportRejectedException(ImportProblem problem, String detail, Long statementId, Integer lineNumber,
                                    ImportPreview preview) {
        super(detail);
        this.problem = problem;
        this.statementId = statementId;
        this.lineNumber = lineNumber;
        this.preview = preview;
    }

    static ImportRejectedException alreadyImported(long statementId) {
        return new ImportRejectedException(ImportProblem.ALREADY_IMPORTED,
                "This file was already imported as statement " + statementId, statementId, null, null);
    }

    static ImportRejectedException parseError(StatementParseException e) {
        // Parser messages carry line numbers and tag or column names only (StatementParseException).
        return new ImportRejectedException(ImportProblem.PARSE_ERROR, e.getMessage(), null, e.getLineNumber(), null);
    }

    static ImportRejectedException mappingRequired(long accountId) {
        return new ImportRejectedException(ImportProblem.MAPPING_REQUIRED, "Account " + accountId
                + " has no CSV mapping. Preview the file with POST /api/accounts/" + accountId + "/imports/preview, "
                + "save its suggestedMapping (edited as needed) with PUT /api/accounts/" + accountId
                + "/csv-mapping, then import again");
    }

    static ImportRejectedException mappingMismatch(long accountId, ImportPreview preview) {
        return new ImportRejectedException(ImportProblem.MAPPING_MISMATCH, "The file's columns don't match account "
                + accountId + "'s CSV mapping. Check the preview, save a mapping for these columns with PUT "
                + "/api/accounts/" + accountId + "/csv-mapping, then import again", null, null, preview);
    }

    static ImportRejectedException mappingChanged() {
        return new ImportRejectedException(ImportProblem.MAPPING_CHANGED,
                "The account's CSV mapping changed while the file was being read; nothing was saved. Retry the import");
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

    /** The file's preview without rows, for {@link ImportProblem#MAPPING_MISMATCH}; otherwise null. */
    public ImportPreview getPreview() {
        return preview;
    }
}
