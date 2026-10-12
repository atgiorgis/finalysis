package com.finalysis.ingestion;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * Why an import was rejected, as an RFC 9457 problem type. Details must never contain file
 * contents, descriptions or account numbers (SEC-10).
 */
public enum ImportProblem {

    UNSUPPORTED_FORMAT(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-format", "Unsupported file format"),
    AMBIGUOUS_FORMAT(HttpStatus.UNPROCESSABLE_CONTENT, "ambiguous-format", "Ambiguous file format"),
    PARSE_ERROR(HttpStatus.UNPROCESSABLE_CONTENT, "parse-error", "Statement could not be read"),
    ACCOUNT_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "account-mismatch", "Statement is for a different account"),
    UNSUPPORTED_CURRENCY(HttpStatus.UNPROCESSABLE_CONTENT, "unsupported-currency", "Unsupported currency"),
    ALREADY_IMPORTED(HttpStatus.CONFLICT, "already-imported", "Statement already imported"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large", "File too large");

    private final HttpStatus status;
    private final URI type;
    private final String title;

    ImportProblem(HttpStatus status, String slug, String title) {
        this.status = status;
        this.type = URI.create("urn:finalysis:problem:" + slug);
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public URI type() {
        return type;
    }

    public String title() {
        return title;
    }
}
