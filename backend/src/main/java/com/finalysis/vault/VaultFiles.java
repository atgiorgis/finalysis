package com.finalysis.vault;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Where a feature may keep files inside the vault (docs/plans/phase-8.md §4.1, §7): only while the vault is unlocked,
 * and never with the vault off (the plain dev database has no encrypted volume). The one vault class features use
 * beyond its two events; it hands out folders, nothing else.
 */
public class VaultFiles {

    private final VaultService vault;

    VaultFiles(VaultService vault) {
        this.vault = vault;
    }

    /** {@code <mount>/tls}: the LAN listener's CA and server keys (§9.2). Empty while locked, or with the vault off. */
    public Optional<Path> tlsDirectory() {
        return folder("tls");
    }

    /** {@code <mount>/statements}: kept statement files. Empty while locked, or with the vault off. */
    public Optional<Path> statementsDirectory() {
        return folder("statements");
    }

    private Optional<Path> folder(String name) {
        if (!vault.enabled() || vault.state() != VaultState.UNLOCKED) {
            return Optional.empty();
        }
        return Optional.of(vault.mountPoint().resolve(name));
    }
}
