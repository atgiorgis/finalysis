package com.finalysis.transfer;

import com.finalysis.account.AccountType;
import com.finalysis.api.ApiMoney;
import com.finalysis.ingestion.SourceSection;
import com.finalysis.ingestion.Txn;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One side of a transfer as the API shows it. The first API to return descriptions: the user needs
 * them to decide, and it's their own data on a local API. Logs and error responses still never carry
 * them (SEC-10).
 */
public record TxnSummary(long id, long accountId, String accountName, AccountType accountType, long statementId,
                         LocalDate txnDate, LocalDate postDate, @ApiMoney BigDecimal amount,
                         SourceSection sourceSection, String rawDescription) {

    /** Null for the absent side of a one-sided link. */
    static TxnSummary from(Txn txn) {
        if (txn == null) {
            return null;
        }
        return new TxnSummary(txn.getId(), txn.getAccount().getId(), txn.getAccount().getName(),
                txn.getAccount().getType(), txn.getStatement().getId(), txn.getTxnDate(), txn.getPostDate(),
                txn.getAmount(), txn.getSourceSection(), txn.getRawDescription());
    }

    /** {@code coalesce(post_date, txn_date)}, the date detection compares (§2.1 rule 3). */
    LocalDate effectiveDate() {
        return postDate != null ? postDate : txnDate;
    }
}
