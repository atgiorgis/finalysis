package com.finalysis.chat;

import java.net.URI;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Chat's API errors (docs/plans/phase-6.md §10.4) as RFC 9457 problems. They extend Spring's
 * {@link ErrorResponseException}, which {@code ApiExceptionHandler} (a {@code ResponseEntityExceptionHandler}) renders as
 * they are, so {@code api} needs nothing from chat. Details are fixed texts: never a question, an answer, a merchant or
 * an exception's message.
 */
public class ChatProblem extends ErrorResponseException {

    private ChatProblem(HttpStatus status, String type, String title, String detail, Throwable cause) {
        super(status, problem(status, type, title, detail), cause);
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:finalysis:problem:" + type));
        problem.setTitle(title);
        return problem;
    }

    /** 503: no AI profile. Nothing is stored and no call is made. */
    public static ChatProblem aiOff() {
        return new ChatProblem(HttpStatus.SERVICE_UNAVAILABLE, "ai-off", "AI is off",
                "AI is off. Start the backend with the aws profile to use chat.", null);
    }

    /** 429: the day's estimated cost reached the cap; resets at local midnight. */
    public static ChatProblem budget(OffsetDateTime resetsAt) {
        ChatProblem problem = new ChatProblem(HttpStatus.TOO_MANY_REQUESTS, "chat-budget", "Chat budget spent",
                "Today's chat budget is spent. It resets at local midnight.", null);
        problem.getBody().setProperty("resetsAt", resetsAt.toString());
        return problem;
    }

    /** 409: another turn of the same conversation is running. */
    public static ChatProblem turnInProgress() {
        return new ChatProblem(HttpStatus.CONFLICT, "turn-in-progress", "Turn in progress",
                "Another question in this conversation is still being answered.", null);
    }

    /** 429: the overall turn limit stayed full for the whole wait. */
    public static ChatProblem busy() {
        return new ChatProblem(HttpStatus.TOO_MANY_REQUESTS, "chat-busy", "Chat is busy",
                "Other questions are being answered. Try again in a moment.", null);
    }

    /**
     * 502: a model call failed after the SDK's retries; the turn was stored as an error. The cause stays out, so an SDK
     * message is never rendered or logged with the problem.
     */
    public static ChatProblem aiUnavailable(int turn) {
        ChatProblem problem = aiUnavailable();
        problem.getBody().setProperty("turn", turn);
        return problem;
    }

    /** 502: a model call failed after the SDK's retries; nothing was stored (the monthly summary, §11.1). */
    public static ChatProblem aiUnavailable() {
        return new ChatProblem(HttpStatus.BAD_GATEWAY, "ai-unavailable", "AI unavailable",
                "The model could not answer. Try again later.", null);
    }
}
