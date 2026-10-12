package com.finalysis.ingestion;

import com.finalysis.account.AccountType;
import com.finalysis.ingestion.csv.CsvMappingSpec;
import java.time.LocalDate;
import java.util.Objects;

/**
 * What a parser may know besides the file: the target account and the upload's request values.
 *
 * @param csvMapping  a snapshot of the account's saved CSV mapping, or null when it has none; only
 *                    parsers that {@linkplain StatementParser#usesCsvMapping() use it} get one
 * @param periodStart the request's period override, or null; a parser only needs it for a file
 *                    without rows, whose period it can't read
 * @param periodEnd   likewise
 */
public record ParseContext(AccountType accountType, CsvMappingSpec csvMapping, LocalDate periodStart,
                           LocalDate periodEnd) {

    public ParseContext {
        Objects.requireNonNull(accountType, "accountType");
    }

    /** A context without a request period. */
    public ParseContext(AccountType accountType, CsvMappingSpec csvMapping) {
        this(accountType, csvMapping, null, null);
    }

    /** A context without a CSV mapping or request period. */
    public ParseContext(AccountType accountType) {
        this(accountType, null);
    }
}
