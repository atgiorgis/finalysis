package com.finalysis.ingestion.csv;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A mapping suggested for a file (ING-01), for the user to check and save with
 * {@code PUT /api/accounts/{id}/csv-mapping}; nothing is saved automatically.
 *
 * @param mapping    a complete {@code PUT} body; fields that weren't found are null (or an empty
 *                   description list), so saving it unedited fails until they are filled in
 * @param confidence per mapping field, in field order. Optional columns that weren't found are
 *                   left out; required ones are reported as {@link Confidence#MISSING}
 * @param notes      what the user should check before saving
 */
public record SuggestedCsvMapping(CsvMappingRequest mapping, Map<String, Confidence> confidence, List<String> notes) {

    public enum Confidence {
        /** A header name from the synonym list. */
        MATCHED,
        /** Inferred from the rows, or a default; check it. */
        GUESSED,
        /** Not found; the user must set it. */
        MISSING
    }

    public SuggestedCsvMapping {
        confidence = Collections.unmodifiableMap(new LinkedHashMap<>(confidence));
        notes = List.copyOf(notes);
    }

    /** No header names, notes or mapping values: they come from the user's file (SEC-10). */
    @Override
    public String toString() {
        return "SuggestedCsvMapping[" + confidence.size() + " fields, " + notes.size() + " notes]";
    }
}
