package com.finalysis.vault;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The recovery key (docs/plans/phase-8.md §4.2): 256 random bits, shown as 52 Crockford base32 characters in 13 groups
 * of four. Its 52 characters, without dashes, are the disk image's passphrase. Typed back, it is read leniently:
 * dashes and spaces are ignored, lower case is upper case, {@code O} is {@code 0} and {@code I}/{@code L} are
 * {@code 1}. Close it to zero the bytes.
 */
final class RecoveryKey implements AutoCloseable {

    static final int BYTES = 32;
    static final int CHARS = 52;
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final byte[] bytes;

    private RecoveryKey(byte[] bytes) {
        this.bytes = bytes;
    }

    static RecoveryKey generate(SecureRandom random) {
        byte[] bytes = new byte[BYTES];
        random.nextBytes(bytes);
        return new RecoveryKey(bytes);
    }

    /** Takes ownership of {@code bytes} (zeroed on close). */
    static RecoveryKey of(byte[] bytes) {
        if (bytes.length != BYTES) {
            throw new IllegalArgumentException("a recovery key has 32 bytes");
        }
        return new RecoveryKey(bytes);
    }

    /** Reads a typed key; empty when it isn't one (wrong length, a character outside the alphabet, stray bits). */
    static java.util.Optional<RecoveryKey> parse(String text) {
        if (text == null) {
            return java.util.Optional.empty();
        }
        char[] chars = new char[CHARS];
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toUpperCase(text.charAt(i));
            if (c == '-' || Character.isWhitespace(c)) {
                continue;
            }
            if (n == CHARS) {
                Arrays.fill(chars, '\0');
                return java.util.Optional.empty();
            }
            chars[n++] = switch (c) {
                case 'O' -> '0';
                case 'I', 'L' -> '1';
                default -> c;
            };
        }
        try {
            if (n != CHARS) {
                return java.util.Optional.empty();
            }
            byte[] bytes = new byte[BYTES];
            int buffer = 0;
            int bits = 0;
            int out = 0;
            for (char c : chars) {
                int value = indexOf(c);
                if (value < 0) {
                    Arrays.fill(bytes, (byte) 0);
                    return java.util.Optional.empty();
                }
                buffer = (buffer << 5) | value;
                bits += 5;
                if (bits >= 8) {
                    bits -= 8;
                    if (out < BYTES) {
                        bytes[out++] = (byte) (buffer >> bits);
                    }
                    buffer &= (1 << bits) - 1;
                }
            }
            if (out != BYTES || buffer != 0) {
                Arrays.fill(bytes, (byte) 0);
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new RecoveryKey(bytes));
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /** The 52 characters, the image's passphrase. The caller zeroes the array. */
    char[] passphrase() {
        char[] chars = new char[CHARS];
        int buffer = 0;
        int bits = 0;
        int n = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                chars[n++] = ALPHABET[(buffer >> bits) & 31];
            }
            buffer &= (1 << bits) - 1;
        }
        chars[n] = ALPHABET[(buffer << (5 - bits)) & 31];
        return chars;
    }

    /** The key as shown once at setup: 13 groups of four, dash-separated. */
    String formatted() {
        char[] chars = passphrase();
        StringBuilder text = new StringBuilder(CHARS + 12);
        for (int i = 0; i < CHARS; i++) {
            if (i > 0 && i % 4 == 0) {
                text.append('-');
            }
            text.append(chars[i]);
        }
        Arrays.fill(chars, '\0');
        return text.toString();
    }

    /** The raw bytes, for wrapping. Not a copy. */
    byte[] bytes() {
        return bytes;
    }

    @Override
    public void close() {
        Arrays.fill(bytes, (byte) 0);
    }

    private static int indexOf(char c) {
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == c) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return "RecoveryKey[hidden]";
    }
}
