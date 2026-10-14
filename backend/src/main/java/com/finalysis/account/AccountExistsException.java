package com.finalysis.account;

/**
 * An account with the same institution (ignoring case and surrounding spaces), type and last four
 * already exists (V12 {@code uq_account_identity}). Mapped to 409 {@code account-exists} with the
 * existing account's id only, so a client can reuse it.
 */
public class AccountExistsException extends RuntimeException {

    private final long accountId;

    public AccountExistsException(long accountId) {
        super("An account with this institution, type and last four digits already exists");
        this.accountId = accountId;
    }

    public long getAccountId() {
        return accountId;
    }
}
