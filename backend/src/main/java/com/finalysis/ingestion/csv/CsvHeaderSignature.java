package com.finalysis.ingestion.csv;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * What a mapping was saved against, so an import can tell when a file's layout has changed
 * (step 7, {@code mapping-mismatch}). With a header row: {@code sha256:} and the SHA-256 of the
 * header names, each stripped, joined with U+001F (a separator that never appears in a header).
 * Without one: {@code columns:} and the column count.
 */
public final class CsvHeaderSignature {

    private static final char SEPARATOR = '\u001F';

    private CsvHeaderSignature() {
    }

    public static String ofHeaders(List<String> headers) {
        String joined = String.join(String.valueOf(SEPARATOR), headers.stream().map(String::strip).toList());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    public static String ofColumnCount(int columnCount) {
        return "columns:" + columnCount;
    }
}
