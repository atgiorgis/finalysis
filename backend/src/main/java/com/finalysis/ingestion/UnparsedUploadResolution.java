package com.finalysis.ingestion;

/** How an unparsed upload left the review queue (ING-13, {@code unparsed_upload.resolution}, V11). */
public enum UnparsedUploadResolution {
    /** Uploaded again, recognized and imported. Stays RESOLVED if that statement is deleted later. */
    RESOLVED,
    /** The user dismissed it; nothing was imported. */
    DISMISSED
}
