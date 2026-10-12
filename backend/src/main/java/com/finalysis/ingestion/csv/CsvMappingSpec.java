package com.finalysis.ingestion.csv;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * An immutable snapshot of an account's saved mapping, taken in a read transaction before parsing
 * ({@link CsvMappingService#snapshot}), so the parser never touches JPA. {@code updatedAt} is the
 * mapping's version: the import checks under the account lock that it is still current, so a file
 * is never saved with fingerprints from a mapping that changed while it was being parsed.
 */
public record CsvMappingSpec(CsvMappingValues values, OffsetDateTime updatedAt) {

    public CsvMappingSpec {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
