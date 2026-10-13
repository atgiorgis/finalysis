package com.finalysis.ingestion;

/** Where an unparsed upload stands (ING-13), derived from {@code resolution} (V11). */
public enum UnparsedUploadState {
    /** Waiting for a decision: in the review queue. */
    OPEN,
    /**
     * Uploaded again, recognized and imported as {@code resolvedStatementId}. Still RESOLVED, with a
     * null statement id, once that statement is deleted.
     */
    RESOLVED,
    /** The user dismissed it; nothing was imported. */
    DISMISSED;

    static UnparsedUploadState of(UnparsedUpload upload) {
        if (upload.getResolution() == null) {
            return OPEN;
        }
        return switch (upload.getResolution()) {
            case RESOLVED -> RESOLVED;
            case DISMISSED -> DISMISSED;
        };
    }
}
