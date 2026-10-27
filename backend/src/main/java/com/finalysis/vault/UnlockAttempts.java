package com.finalysis.vault;

import java.time.Duration;
import java.time.Instant;

/**
 * The unlock rate limit (docs/plans/phase-8.md §5.2), in memory since there is no database while locked: after five
 * wrong passwords or recovery keys in a row, each further attempt waits, from 30 seconds doubling to 15 minutes. A
 * success resets it. Shared by unlock, recovery and password change.
 */
final class UnlockAttempts {

    static final int FREE_ATTEMPTS = 5;
    static final Duration FIRST_WAIT = Duration.ofSeconds(30);
    static final Duration MAX_WAIT = Duration.ofMinutes(15);

    private final VaultClock clock;
    private int failures;
    private Instant blockedUntil = Instant.MIN;

    UnlockAttempts(VaultClock clock) {
        this.clock = clock;
    }

    /** Throws {@code too-many-attempts} while blocked. */
    synchronized void check() {
        Instant now = clock.wall();
        if (now.isBefore(blockedUntil)) {
            throw VaultException.tooManyAttempts(Duration.between(now, blockedUntil));
        }
    }

    synchronized void failed() {
        failures++;
        if (failures >= FREE_ATTEMPTS) {
            int doublings = Math.min(failures - FREE_ATTEMPTS, 10);
            Duration wait = FIRST_WAIT.multipliedBy(1L << doublings);
            if (wait.compareTo(MAX_WAIT) > 0) {
                wait = MAX_WAIT;
            }
            blockedUntil = clock.wall().plus(wait);
        }
    }

    synchronized void succeeded() {
        failures = 0;
        blockedUntil = Instant.MIN;
    }
}
