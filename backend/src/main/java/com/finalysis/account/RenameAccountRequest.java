package com.finalysis.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of PATCH /api/accounts/{id}. Only the name can change; other fields are rejected. */
public record RenameAccountRequest(@NotBlank @Size(max = Account.NAME_MAX) String name) {

    public RenameAccountRequest {
        name = name == null ? null : name.strip();
    }
}
