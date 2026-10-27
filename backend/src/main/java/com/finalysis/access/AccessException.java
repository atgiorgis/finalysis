package com.finalysis.access;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/** A refused access request, as {@code urn:finalysis:problem:<code>}; fixed sentences, never the request. */
public class AccessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Duration retryAfter;

    AccessException(HttpStatus status, String code, String message, Duration retryAfter) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    AccessException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    static AccessException wrongPassword() {
        return new AccessException(HttpStatus.UNAUTHORIZED, "wrong-password", "The password is wrong.");
    }

    static AccessException tooManyAttempts(Duration wait) {
        long seconds = Math.max(1, wait.toSeconds());
        String when = seconds < 90 ? seconds + " seconds" : ((seconds + 59) / 60) + " minutes";
        return new AccessException(HttpStatus.TOO_MANY_REQUESTS, "too-many-attempts",
                "Too many wrong attempts. Wait " + when + " and try again.", wait);
    }

    static AccessException lanOnly() {
        return new AccessException(HttpStatus.FORBIDDEN, "lan-only", "Logging in is for the phone; this computer "
                + "doesn't need it.");
    }

    static AccessException localOnly() {
        return new AccessException(HttpStatus.FORBIDDEN, "local-only",
                "Phone access is set on the computer Finalysis runs on, not from the phone.");
    }

    static AccessException settingInvalid(String why) {
        return new AccessException(HttpStatus.UNPROCESSABLE_CONTENT, "setting-invalid", why);
    }

    static AccessException noCertificate() {
        return new AccessException(HttpStatus.NOT_FOUND, "no-certificate",
                "There is no certificate yet: turn phone access on first.");
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    Duration retryAfter() {
        return retryAfter;
    }
}
