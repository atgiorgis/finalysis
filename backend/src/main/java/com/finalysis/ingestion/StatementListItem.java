package com.finalysis.ingestion;

import com.finalysis.api.ApiMoney;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One statement in {@code GET /api/accounts/{id}/statements}: the detail without the warnings,
 * which {@code GET /api/statements/{id}} returns.
 *
 * @param parsedCount      rows in the file; null for statements imported before reconciliation existed
 * @param transactionCount rows this statement owns (the rest were duplicates of earlier imports)
 */
public record StatementListItem(long id, String parser, LocalDate periodStart, LocalDate periodEnd,
                                @ApiMoney BigDecimal openingBalance, @ApiMoney BigDecimal closingBalance,
                                StatementStatus status, BalanceCheck balanceCheck, Integer parsedCount,
                                long transactionCount, int warningCount, OffsetDateTime importedAt,
                                OffsetDateTime reviewedAt) {

    static StatementListItem from(Statement statement, long transactionCount) {
        return new StatementListItem(statement.getId(), statement.getParser(), statement.getPeriodStart(),
                statement.getPeriodEnd(), statement.getOpeningBalance(), statement.getClosingBalance(),
                statement.getStatus(), statement.getBalanceCheck(), statement.getParsedCount(), transactionCount,
                statement.getImportWarnings().size(), statement.getImportedAt(), statement.getReviewedAt());
    }
}
