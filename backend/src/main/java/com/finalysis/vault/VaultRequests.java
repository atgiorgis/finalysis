package com.finalysis.vault;

import java.time.Duration;

/**
 * Counts the gated requests in flight, so a lock can wait for them (docs/plans/phase-8.md §4.6): up to 30 seconds,
 * after which closing the pool cancels what is left.
 */
final class VaultRequests {

    private int inFlight;

    synchronized void enter() {
        inFlight++;
    }

    synchronized void exit() {
        inFlight--;
        notifyAll();
    }

    synchronized int inFlight() {
        return inFlight;
    }

    /** Waits until none is in flight or the timeout passes; true when none is left. */
    synchronized boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (inFlight > 0) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            try {
                wait(Math.max(1, left / 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return inFlight == 0;
            }
        }
        return true;
    }
}
