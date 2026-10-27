package com.finalysis.vault;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Argon2id's cost (docs/plans/phase-8.md §4.2), stored in the key file. Bouncy Castle's Argon2 (version 1.3).
 *
 * @param memoryKiB   memory in KiB
 * @param iterations  passes over the memory
 * @param parallelism lanes
 */
record KdfParameters(int memoryKiB, int iterations, int parallelism) {

    /** The production floor: 64 MiB, 3 iterations, 1 lane. */
    static final KdfParameters FLOOR = new KdfParameters(64 * 1024, 3, 1);

    /** Upper bounds a key file may name; beyond them it is damaged, not merely slow. */
    static final KdfParameters CEILING = new KdfParameters(4 * 1024 * 1024, 64, 16);

    boolean atLeast(KdfParameters floor) {
        return memoryKiB >= floor.memoryKiB && iterations >= floor.iterations && parallelism >= floor.parallelism;
    }

    boolean atMost(KdfParameters ceiling) {
        return memoryKiB <= ceiling.memoryKiB && iterations <= ceiling.iterations && parallelism <= ceiling.parallelism;
    }

    /** Derives a 32-byte key from the password; the caller zeroes it. The password's chars are left as they are. */
    byte[] derive(char[] password, byte[] salt) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(memoryKiB)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] passwordBytes = new byte[encoded.remaining()];
        encoded.get(passwordBytes);
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }
        byte[] key = new byte[32];
        try {
            generator.generateBytes(passwordBytes, key);
            return key;
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
        }
    }
}
