package com.finalysis.api;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Times the app sets itself (a confirmation, a review), in the form Postgres returns a
 * {@code TIMESTAMPTZ}: UTC, to the microsecond. A response built right after the write then shows the
 * same value as every later read, instead of the server's local offset and extra precision.
 */
public final class Timestamps {

    private Timestamps() {
    }

    public static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
