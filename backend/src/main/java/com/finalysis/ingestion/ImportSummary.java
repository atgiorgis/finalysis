package com.finalysis.ingestion;

import java.time.LocalDate;
import java.util.List;

/**
 * Result of one import.
 *
 * @param parsed     rows read from the file
 * @param inserted   rows saved as new transactions
 * @param duplicates rows skipped because an identical row was already imported (ING-03)
 * @param warnings   line numbers, counts and statement ids only; never file contents (SEC-10)
 */
public record ImportSummary(long statementId, String parser, LocalDate periodStart, LocalDate periodEnd,
                            int parsed, int inserted, int duplicates, StatementStatus status,
                            List<String> warnings) {

    public ImportSummary {
        warnings = List.copyOf(warnings);
    }
}
