package com.finalysis.ingestion;

import com.finalysis.api.ApiMoney;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One imported statement. Balances are JSON strings with two decimals ({@link ApiMoney}).
 * {@code parsedCount} counts the file's rows (null for statements imported before reconciliation
 * existed), {@code transactionCount} the rows this statement owns; {@code warnings} explain the
 * status, including every failed balance check with its expected and actual amounts.
 */
public record StatementResponse(Long id, Long accountId, String parser, LocalDate periodStart, LocalDate periodEnd,
                                @ApiMoney BigDecimal openingBalance, @ApiMoney BigDecimal closingBalance,
                                StatementStatus status, BalanceCheck balanceCheck,
                                List<String> warnings, Integer parsedCount, long transactionCount,
                                OffsetDateTime importedAt) {

    static StatementResponse from(Statement statement, long transactionCount) {
        return new StatementResponse(statement.getId(), statement.getAccount().getId(), statement.getParser(),
                statement.getPeriodStart(), statement.getPeriodEnd(), statement.getOpeningBalance(),
                statement.getClosingBalance(), statement.getStatus(), statement.getBalanceCheck(),
                statement.getImportWarnings(), statement.getParsedCount(), transactionCount, statement.getImportedAt());
    }
}
