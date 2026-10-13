package com.finalysis.ingestion;

/** Where an unparsed upload stands (ING-13), derived from {@code resolved_at} and the resolving statement. */
public enum UnparsedUploadState {
    /** Waiting for a decision: in the review queue. */
    OPEN,
    /** Uploaded again, recognized and imported as {@code resolvedStatementId}. */
    RESOLVED,
    /**
     * The user dismissed it; nothing was imported. V8 stores this as {@code resolved_at} without a
     * statement, so an upload whose resolving statement was deleted later reads as DISMISSED too.
     */
    DISMISSED;

    static UnparsedUploadState of(UnparsedUpload upload) {
        if (upload.getResolvedAt() == null) {
            return OPEN;
        }
        return upload.getResolvedStatementId() != null ? RESOLVED : DISMISSED;
    }
}
