package com.finalysis.ingestion;

import java.time.OffsetDateTime;

/**
 * An unparsed upload (ING-13): the 202 Accepted body for a PDF that no layout parser recognized, and
 * the body of {@code GET /api/unparsed-uploads/{id}} and its dismiss action.
 *
 * @param state               OPEN, RESOLVED or DISMISSED (see {@link UnparsedUploadState})
 * @param resolvedAt          when it was resolved or dismissed; null while open
 * @param resolvedStatementId the statement that resolved it; null while open, when dismissed, or once
 *                            that statement is deleted (the state stays RESOLVED)
 * @param detail              fixed text for the state, never file contents
 */
public record UnparsedUploadResponse(long id, long accountId, UnparsedReason reason, String fileName,
                                     OffsetDateTime createdAt, UnparsedUploadState state, OffsetDateTime resolvedAt,
                                     Long resolvedStatementId, String detail) {

    static final String DETAIL = "No supported statement layout was recognized; nothing was imported. "
            + "The file is held for review.";
    static final String RESOLVED_DETAIL = "The file was uploaded again and imported.";
    static final String RESOLVED_DELETED_DETAIL = "The file was uploaded again and imported; that statement "
            + "has since been deleted.";
    static final String DISMISSED_DETAIL = "Dismissed; nothing was imported.";

    static UnparsedUploadResponse from(UnparsedUpload upload) {
        UnparsedUploadState state = UnparsedUploadState.of(upload);
        String detail = switch (state) {
            case OPEN -> DETAIL;
            case RESOLVED -> upload.getResolvedStatementId() != null ? RESOLVED_DETAIL : RESOLVED_DELETED_DETAIL;
            case DISMISSED -> DISMISSED_DETAIL;
        };
        return new UnparsedUploadResponse(upload.getId(), upload.getAccount().getId(), upload.getReason(),
                upload.getFileName(), upload.getCreatedAt(), state, upload.getResolvedAt(),
                upload.getResolvedStatementId(), detail);
    }

    /** Ids, reason and state only: the file name can identify the user (SEC-10). */
    @Override
    public String toString() {
        return "UnparsedUploadResponse[" + id + ", account " + accountId + ", " + reason + ", " + state + "]";
    }
}
