package com.finalysis.alert;

import com.finalysis.api.ApiMoney;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A txn an alert is about. Only the cleaned merchant, never the raw description, a counterparty or an account
 * number: Phase 6 decides what may go to a cloud model, and this keeps the default small (docs/plans/phase-5.md §5.3).
 *
 * @param amount signed, GR-03: negative = money out
 */
public record TxnRef(long id, long accountId, LocalDate txnDate, @ApiMoney BigDecimal amount, String merchant) {
}
