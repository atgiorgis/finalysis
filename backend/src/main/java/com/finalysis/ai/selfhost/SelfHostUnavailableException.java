package com.finalysis.ai.selfhost;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 409 {@code selfhost-unavailable} (docs/plans/phase-9.md §5.5): Start or Stop can't be done now, and the reason in
 * plain words: no Cloud credentials (the {@code aws} profile), no region, no tagged server or two, a server still
 * stopping, or an AWS refusal or failure. Nothing changed.
 */
class SelfHostUnavailableException extends ErrorResponseException {

    private final String reason;

    SelfHostUnavailableException(String reason) {
        super(HttpStatus.CONFLICT, problem(reason), null);
        this.reason = reason;
    }

    String reason() {
        return reason;
    }

    private static ProblemDetail problem(String reason) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, reason);
        problem.setType(URI.create("urn:finalysis:problem:selfhost-unavailable"));
        problem.setTitle("Self-hosted server unavailable");
        problem.setProperty("reason", reason);
        return problem;
    }
}
