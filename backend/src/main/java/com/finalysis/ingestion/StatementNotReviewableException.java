package com.finalysis.ingestion;

/** Only NEEDS_REVIEW statements can be marked reviewed (ING-07). Mapped to 409 {@code not-reviewable}. */
public class StatementNotReviewableException extends RuntimeException {

    public StatementNotReviewableException(long statementId, StatementStatus status) {
        super("Statement " + statementId + " is " + status + "; only NEEDS_REVIEW statements can be reviewed");
    }
}
