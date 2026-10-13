package com.finalysis.ingestion;

/** An unparsed upload that is already resolved or dismissed (ING-13). Mapped to 409 {@code already-resolved}. */
public class UploadAlreadyResolvedException extends RuntimeException {

    public UploadAlreadyResolvedException(long uploadId, UnparsedUploadState state) {
        super("Unparsed upload " + uploadId + " is already " + state);
    }
}
