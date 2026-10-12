package com.finalysis.ingestion;

import com.finalysis.api.ApiMoney;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One imported statement. Balances are JSON strings with two decimals ({@link ApiMoney}).
 * {@code transactionCount} counts the rows this statement owns;
 * {@code warnings} are the import's warnings, which explain a NEEDS_REVIEW status.
 */
public record StatementResponse(Long id, Long accountId, String parser, LocalDate periodStart, LocalDate periodEnd,
                                @ApiMoney BigDecimal openingBalance, @ApiMoney BigDecimal closingBalance,
                                StatementStatus status,
                                List<String> warnings, long transactionCount, OffsetDateTime importedAt) {

    static StatementResponse from(Statement statement, long transactionCount) {
        return new StatementResponse(statement.getId(), statement.getAccount().getId(), statement.getParser(),
                statement.getPeriodStart(), statement.getPeriodEnd(), statement.getOpeningBalance(),
                statement.getClosingBalance(), statement.getStatus(), statement.getImportWarnings(), transactionCount,
                statement.getImportedAt());
    }
}
