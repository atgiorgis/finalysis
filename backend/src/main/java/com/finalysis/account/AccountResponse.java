package com.finalysis.account;

import java.time.OffsetDateTime;

public record AccountResponse(
        Long id, String name, String institution, AccountType type, String lastFour, OffsetDateTime createdAt) {

    static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getName(), account.getInstitution(),
                account.getType(), account.getLastFour(), account.getCreatedAt());
    }
}
