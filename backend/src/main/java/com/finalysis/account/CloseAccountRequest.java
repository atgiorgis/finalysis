package com.finalysis.account;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Body of {@code PUT /api/accounts/{id}/closure}: the last day the account was open ({@code yyyy-MM-dd}). */
public record CloseAccountRequest(@NotNull LocalDate closedOn) {
}
