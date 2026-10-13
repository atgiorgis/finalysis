package com.finalysis.ingestion;

import java.time.OffsetDateTime;

/**
 * The 202 Accepted body for a PDF that no layout parser recognized (ING-13): nothing was imported,
 * and the upload is held for review. No {@code Location}: the review-queue API comes in 3c.
 *
 * @param detail fixed text, never file contents
 */
public record UnparsedUploadResponse(long id, long accountId, UnparsedReason reason, String fileName,
                                     OffsetDateTime createdAt, String detail) {

    static final String DETAIL = "No supported statement layout was recognized; nothing was imported. "
            + "The file is held for review.";

    static UnparsedUploadResponse from(UnparsedUpload upload) {
        return new UnparsedUploadResponse(upload.getId(), upload.getAccount().getId(), upload.getReason(),
                upload.getFileName(), upload.getCreatedAt(), DETAIL);
    }

    /** Ids and reason only: the file name can identify the user (SEC-10). */
    @Override
    public String toString() {
        return "UnparsedUploadResponse[" + id + ", account " + accountId + ", " + reason + "]";
    }
}
