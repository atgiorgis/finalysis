package com.finalysis.ingestion;

import com.finalysis.account.AccountType;
import com.finalysis.ingestion.csv.CsvMapping;
import java.util.Objects;

/**
 * What a parser may know about the target account.
 *
 * @param csvMapping the account's saved CSV mapping, or null when it has none; only the CSV parser
 *                   reads it, and it must not be modified
 */
public record ParseContext(AccountType accountType, CsvMapping csvMapping) {

    public ParseContext {
        Objects.requireNonNull(accountType, "accountType");
    }

    /** A context without a CSV mapping. */
    public ParseContext(AccountType accountType) {
        this(accountType, null);
    }
}
