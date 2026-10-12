package com.finalysis.ingestion;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One upload. The optional period overrides the file's; the optional balances fill in only
 * what the file doesn't print.
 *
 * @param fileName       the client's file name, unchecked
 * @param openingBalance money text as sent, e.g. "1234.56", unchecked
 * @param closingBalance money text as sent, unchecked
 */
public record ImportRequest(long accountId, String fileName, byte[] bytes, LocalDate periodStart, LocalDate periodEnd,
                            String openingBalance, String closingBalance) {

    public ImportRequest {
        Objects.requireNonNull(bytes, "bytes");
    }

    /** No file contents or name: they can identify the user (SEC-10). */
    @Override
    public String toString() {
        return "ImportRequest[account " + accountId + ", " + bytes.length + " bytes]";
    }
}
