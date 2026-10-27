package com.finalysis.vault;

import com.finalysis.account.AccountRepository;

/** A deliberate violation for {@code PackageDependencyTest}: vault code that depends on a feature. Test-only. */
@SuppressWarnings("unused")
public class UsesAccount {
    private AccountRepository accounts;
}
