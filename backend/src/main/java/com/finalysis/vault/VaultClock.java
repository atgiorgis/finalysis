package com.finalysis.vault;

import java.time.Instant;

/**
 * Two clocks for the idle and wake locks (docs/plans/phase-8.md §5.3): the wall clock, and a monotonic one that stops
 * while the machine sleeps ({@code System.nanoTime()} on macOS and Linux). A gap between them is a sleep. Tests
 * replace it.
 */
public interface VaultClock {

    Instant wall();

    long monotonicNanos();

    VaultClock SYSTEM = new VaultClock() {
        @Override
        public Instant wall() {
            return Instant.now();
        }

        @Override
        public long monotonicNanos() {
            return System.nanoTime();
        }
    };
}
