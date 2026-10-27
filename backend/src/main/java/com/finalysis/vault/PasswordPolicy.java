package com.finalysis.vault;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The vault password rule (docs/plans/phase-8.md §4.5, §16): at least 12 characters, and none of the 1,000 most common
 * passwords ({@code vault/common-passwords.txt}, SecLists, MIT; see its NOTICE), compared ignoring case. For setup,
 * password change and recovery alike.
 */
public class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    private static final String LIST = "/vault/common-passwords.txt";

    private final Set<String> common;

    public PasswordPolicy() {
        this(load());
    }

    PasswordPolicy(Set<String> common) {
        this.common = common.stream().map(p -> p.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    /** Throws {@link VaultException} ({@code password-rejected}) when the password breaks the rule. */
    void check(String password) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH) {
            throw VaultException.passwordRejected("The password needs at least " + MIN_LENGTH + " characters.");
        }
        if (common.contains(password.toLowerCase(Locale.ROOT))) {
            throw VaultException.passwordRejected("That password is one of the most common ones. Choose another.");
        }
    }

    int size() {
        return common.size();
    }

    private static Set<String> load() {
        try (InputStream in = PasswordPolicy.class.getResourceAsStream(LIST)) {
            if (in == null) {
                throw new IllegalStateException(LIST + " is missing");
            }
            return new String(in.readAllBytes(), UTF_8).lines().filter(line -> !line.isEmpty())
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
