package com.finalysis.ingestion;

import com.finalysis.account.AccountType;
import java.util.Objects;

/**
 * What a parser may know about the target account. CSV parsing will add the account's saved
 * column mapping here (Phase 3a, step 5).
 */
public record ParseContext(AccountType accountType) {

    public ParseContext {
        Objects.requireNonNull(accountType, "accountType");
    }
}
