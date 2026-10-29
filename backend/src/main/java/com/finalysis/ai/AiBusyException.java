package com.finalysis.ai;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 409 {@code ai-busy}: a path switch waited for running AI work (a categorization run, a chat turn) and it didn't finish
 * in time (docs/plans/phase-9.md §3.1). Nothing changed.
 */
public class AiBusyException extends ErrorResponseException {

    public AiBusyException(long waitedSeconds) {
        super(HttpStatus.CONFLICT, problem(waitedSeconds), null);
    }

    private static ProblemDetail problem(long waitedSeconds) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Corgi is busy: a categorization run or a chat answer is still running after " + waitedSeconds
                        + " seconds. Try again when it has finished.");
        problem.setType(URI.create("urn:finalysis:problem:ai-busy"));
        problem.setTitle("Corgi is busy");
        problem.setProperty("reason", "AI work still running");
        return problem;
    }
}
