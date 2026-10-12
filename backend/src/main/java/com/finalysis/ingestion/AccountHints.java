package com.finalysis.ingestion;

import com.finalysis.account.AccountType;
import java.util.regex.Pattern;

/**
 * What a file says about the account it belongs to, checked against the account the user chose.
 * Any component may be null when the format doesn't carry it (CSV exports usually carry none).
 *
 * <p>Only the last four digits of an account number are kept; parsers must never pass a full
 * account number out.
 *
 * @param currency ISO 4217 code, e.g. {@code USD}
 */
public record AccountHints(String lastFour, AccountType type, String currency) {

    private static final Pattern LAST_FOUR = Pattern.compile("[0-9]{4}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public AccountHints {
        if (lastFour != null && !LAST_FOUR.matcher(lastFour).matches()) {
            throw new IllegalArgumentException("lastFour must be exactly four digits");
        }
        if (currency != null && !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be a three-letter uppercase code");
        }
    }

    public static AccountHints none() {
        return new AccountHints(null, null, null);
    }
}
