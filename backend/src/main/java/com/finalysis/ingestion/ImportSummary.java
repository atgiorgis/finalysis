package com.finalysis.ingestion;

import com.finalysis.api.ApiMoney;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Result of one import.
 *
 * @param openingBalance as stored: printed, sent, or derived by reconciliation; null when unknown
 * @param parsed         rows read from the file
 * @param inserted       rows saved as new transactions
 * @param duplicates     rows skipped because an identical row was already imported (ING-03)
 * @param warnings       line numbers, counts, ids and balance-check amounts; never descriptions (SEC-10)
 */
public record ImportSummary(long statementId, String parser, LocalDate periodStart, LocalDate periodEnd,
                            @ApiMoney BigDecimal openingBalance, @ApiMoney BigDecimal closingBalance,
                            int parsed, int inserted, int duplicates, StatementStatus status,
                            BalanceCheck balanceCheck, List<String> warnings) {

    public ImportSummary {
        warnings = List.copyOf(warnings);
    }
}
