package com.finalysis.chat;

import com.finalysis.vault.VaultService;

/** A deliberate violation for {@code PackageDependencyTest}: a feature that uses the vault beyond its events. */
@SuppressWarnings("unused")
public class UsesVaultService {
    private VaultService vault;
}
