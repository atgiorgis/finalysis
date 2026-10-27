package com.finalysis.vault;

import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Problem responses for the vault's endpoints (docs/plans/phase-8.md §5.2): {@code urn:finalysis:problem:<code>}, a
 * fixed sentence, never the request. A NOT_READY problem carries the reason code; too-many-attempts a Retry-After.
 */
@RestControllerAdvice(assignableTypes = VaultController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class VaultExceptionHandler {

    @ExceptionHandler(VaultException.class)
    ResponseEntity<ProblemDetail> handle(VaultException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        problem.setType(URI.create("urn:finalysis:problem:" + ex.code()));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.status()).cacheControl(CacheControl.noStore());
        if (ex.notReadyCheck() != null) {
            problem.setProperty("reason", ex.notReadyCheck().reason());
        }
        if (ex.retryAfter() != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, ex.retryAfter().toSeconds())));
        }
        return response.body(problem);
    }

    /** A body that isn't the expected JSON: a fixed message, never the body. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body");
        problem.setType(URI.create("urn:finalysis:problem:invalid-request"));
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(problem);
    }
}
