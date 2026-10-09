package com.finalysis.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST /api/accounts. Name and institution are trimmed before validation. */
public record CreateAccountRequest(
        @NotBlank @Size(max = Account.NAME_MAX) String name,
        @NotBlank @Size(max = Account.INSTITUTION_MAX) String institution,
        @NotNull AccountType type,
        @NotNull @Pattern(regexp = "\\d{4}", message = "must be exactly 4 digits") String lastFour) {

    public CreateAccountRequest {
        name = name == null ? null : name.strip();
        institution = institution == null ? null : institution.strip();
    }
}
