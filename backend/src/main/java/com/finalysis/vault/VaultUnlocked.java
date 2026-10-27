package com.finalysis.vault;

/**
 * Published once the database is open: after unlock, recovery or setup, or at startup with the vault off
 * (docs/plans/phase-8.md §5.1). What used to run at {@code ApplicationReadyEvent} listens to this instead. Features
 * depend on the vault only for this event and {@link VaultLocking}.
 */
public record VaultUnlocked() {
}
