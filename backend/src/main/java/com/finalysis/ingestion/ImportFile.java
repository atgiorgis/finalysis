package com.finalysis.ingestion;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * An uploaded statement file, held in memory only. The bytes are discarded after parsing; only
 * the hash and file name are kept (on {@code statement}).
 *
 * <p>The byte array is not copied, so neither the caller nor a parser may modify it.
 *
 * @param fileName the client's file name, for display only; never used to choose a parser
 * @param sha256   lowercase hex SHA-256 of {@code bytes}, used to block identical re-uploads (ING-04)
 */
public record ImportFile(String fileName, byte[] bytes, String sha256) {

    public ImportFile {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(sha256, "sha256");
    }

    public static ImportFile of(String fileName, byte[] bytes) {
        return new ImportFile(fileName, bytes, sha256Hex(bytes));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }

    /** Size and hash only: the file name and contents can identify the user (SEC-10). */
    @Override
    public String toString() {
        return "ImportFile[" + bytes.length + " bytes, sha256=" + sha256.substring(0, Math.min(12, sha256.length())) + "]";
    }
}
