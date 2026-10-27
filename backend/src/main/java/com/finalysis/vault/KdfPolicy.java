package com.finalysis.vault;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * Which Argon2id cost to use for a new or changed password, and the least a key file may name
 * (docs/plans/phase-8.md §4.2). Production calibrates to about half a second on this machine, never below
 * {@link KdfParameters#FLOOR}. Tests replace it with a cheap policy.
 */
public class KdfPolicy {

    private static final Duration TARGET = Duration.ofMillis(500);
    private static final int MAX_ITERATIONS = 12;

    private final KdfParameters floor;

    public KdfPolicy() {
        this(KdfParameters.FLOOR);
    }

    KdfPolicy(KdfParameters floor) {
        this.floor = floor;
    }

    KdfParameters floor() {
        return floor;
    }

    /**
     * Measures one derivation at the floor and raises the iterations so one takes about half a second, within the
     * floor and {@value #MAX_ITERATIONS} iterations.
     */
    KdfParameters calibrate() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        char[] probe = "calibration-only".toCharArray();
        long start = System.nanoTime();
        byte[] key = floor.derive(probe, salt);
        long nanos = Math.max(1, System.nanoTime() - start);
        java.util.Arrays.fill(key, (byte) 0);
        long scaled = Math.round((double) floor.iterations() * TARGET.toNanos() / nanos);
        int iterations = (int) Math.max(floor.iterations(), Math.min(MAX_ITERATIONS, scaled));
        return new KdfParameters(floor.memoryKiB(), iterations, floor.parallelism());
    }
}
