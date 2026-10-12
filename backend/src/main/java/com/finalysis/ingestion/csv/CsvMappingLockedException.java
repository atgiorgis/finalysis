package com.finalysis.ingestion.csv;

import java.util.List;

/**
 * A mapping change that would alter fingerprints of a mapping already used for an import, sent
 * without {@code confirmFingerprintChange=true}. Mapped to 409 {@code mapping-locked}.
 */
public class CsvMappingLockedException extends RuntimeException {

    private final List<String> fields;

    CsvMappingLockedException(List<String> fields) {
        super("The mapping has been used for an import, and these fields feed transaction fingerprints: "
                + String.join(", ", fields) + ". Repeat the request with confirmFingerprintChange=true to change them.");
        this.fields = List.copyOf(fields);
    }

    /** The locked fields the request would change, as API field names. */
    public List<String> getFields() {
        return fields;
    }
}
