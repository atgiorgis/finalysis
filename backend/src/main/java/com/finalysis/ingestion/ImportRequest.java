package com.finalysis.ingestion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One upload. The optional period overrides the file's; the optional balances fill in only
 * what the file doesn't print.
 *
 * @param fileName the client's file name, unchecked
 */
public record ImportRequest(long accountId, String fileName, byte[] bytes, LocalDate periodStart, LocalDate periodEnd,
                            BigDecimal openingBalance, BigDecimal closingBalance) {

    public ImportRequest {
        Objects.requireNonNull(bytes, "bytes");
    }

    /** No file contents or name: they can identify the user (SEC-10). */
    @Override
    public String toString() {
        return "ImportRequest[account " + accountId + ", " + bytes.length + " bytes]";
    }
}
