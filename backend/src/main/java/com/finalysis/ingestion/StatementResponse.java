package com.finalysis.ingestion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One imported statement. {@code transactionCount} counts the rows this statement owns. */
public record StatementResponse(Long id, Long accountId, String parser, LocalDate periodStart, LocalDate periodEnd,
                                BigDecimal openingBalance, BigDecimal closingBalance, StatementStatus status,
                                long transactionCount, OffsetDateTime importedAt) {

    static StatementResponse from(Statement statement, long transactionCount) {
        return new StatementResponse(statement.getId(), statement.getAccount().getId(), statement.getParser(),
                statement.getPeriodStart(), statement.getPeriodEnd(), statement.getOpeningBalance(),
                statement.getClosingBalance(), statement.getStatus(), transactionCount, statement.getImportedAt());
    }
}
