package com.finalysis.vault;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/**
 * A vault request that can't be done, as a problem response {@code urn:finalysis:problem:<code>}
 * (docs/plans/phase-8.md §5.2). Messages are fixed sentences: never a password, a key, a path or a tool's output.
 */
public class VaultException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient ReadyCheck notReady;
    private final Duration retryAfter;

    VaultException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null);
    }

    private VaultException(HttpStatus status, String code, String message, ReadyCheck notReady, Duration retryAfter) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.notReady = notReady;
        this.retryAfter = retryAfter;
    }

    static VaultException wrongPassword() {
        return new VaultException(HttpStatus.UNAUTHORIZED, "wrong-password", "The password is wrong.");
    }

    static VaultException wrongRecoveryKey() {
        return new VaultException(HttpStatus.UNAUTHORIZED, "wrong-recovery-key",
                "The recovery key doesn't open this vault. Check it and try again.");
    }

    static VaultException tooManyAttempts(Duration retryAfter) {
        return new VaultException(HttpStatus.TOO_MANY_REQUESTS, "too-many-attempts",
                "Too many wrong attempts. Wait " + humanDuration(retryAfter) + " and try again.", null, retryAfter);
    }

    static VaultException passwordRejected(String why) {
        return new VaultException(HttpStatus.UNPROCESSABLE_CONTENT, "password-rejected", why);
    }

    static VaultException notReady(ReadyCheck check) {
        return new VaultException(HttpStatus.SERVICE_UNAVAILABLE, "vault-not-ready", check.message(), check, null);
    }

    static VaultException state(VaultState state, String what) {
        return new VaultException(HttpStatus.CONFLICT, "vault-state",
                "The vault is " + state.name().toLowerCase().replace('_', ' ') + "; " + what);
    }

    static VaultException fileDamaged() {
        return new VaultException(HttpStatus.INTERNAL_SERVER_ERROR, "vault-file-damaged",
                "The vault's key file is damaged or unreadable. Unlock with your recovery key instead; it sets a new "
                        + "password.");
    }

    static VaultException mountFailed() {
        return new VaultException(HttpStatus.INTERNAL_SERVER_ERROR, "mount-failed",
                "The vault's disk image could not be opened. Nothing was changed.");
    }

    static VaultException databaseFailed() {
        return new VaultException(HttpStatus.INTERNAL_SERVER_ERROR, "database-failed",
                "The vault opened, but its database did not start or its schema did not check out. The vault was "
                        + "locked again.");
    }

    static VaultException setupFailed() {
        return new VaultException(HttpStatus.INTERNAL_SERVER_ERROR, "setup-failed",
                "Setup did not finish, and what it had made was removed. Nothing else was changed; try again.");
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    ReadyCheck notReadyCheck() {
        return notReady;
    }

    Duration retryAfter() {
        return retryAfter;
    }

    static String humanDuration(Duration duration) {
        long seconds = Math.max(1, duration.toSeconds());
        if (seconds < 90) {
            return seconds + " seconds";
        }
        return ((seconds + 59) / 60) + " minutes";
    }
}
