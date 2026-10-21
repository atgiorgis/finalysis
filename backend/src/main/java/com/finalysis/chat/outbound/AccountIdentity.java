package com.finalysis.chat.outbound;

/**
 * An account's identifiers, read locally so a question that names an account can be sent with its alias instead
 * (docs/plans/phase-6.md §12.4). Never sent, never logged.
 */
public record AccountIdentity(long id, String name, String institution, String lastFour) {
}
