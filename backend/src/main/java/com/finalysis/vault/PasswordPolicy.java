package com.finalysis.vault;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The vault password rule (docs/plans/phase-8.md §4.5, §16; step 2 review), for setup, password change and recovery:
 *
 * <ul>
 *   <li>12 to 256 characters;</li>
 *   <li>not one of the 1,000 most common passwords ({@code vault/common-passwords.txt}, SecLists, MIT; see its
 *       NOTICE) <b>once or repeated, padded at either end with digits or symbols</b> ({@code dragondragon},
 *       {@code !!monkey2024}, {@code 123456123456}); a password with no letter at all is refused when it contains one
 *       of the list's letter-free entries (years, PINs) anywhere. Compared ignoring case;</li>
 *   <li>at password change, when the database is open: none of the user's account names or institutions, ignoring
 *       case, spaces and punctuation ({@link #checkPersonal}). A name with fewer than four letters and digits is not
 *       checked: it would refuse too much.</li>
 * </ul>
 *
 * No rejection quotes the password or the name it matched.
 */
public class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 256;
    static final int MIN_PERSONAL_TERM = 4;
    private static final String LIST = "/vault/common-passwords.txt";

    private final Set<String> common;
    private final Set<String> letterFree;
    private final int longestCommon;

    public PasswordPolicy() {
        this(load());
    }

    PasswordPolicy(Set<String> common) {
        this.common = common.stream().map(p -> p.toLowerCase(Locale.ROOT)).filter(p -> !p.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.letterFree = this.common.stream().filter(p -> p.codePoints().noneMatch(Character::isLetter))
                .collect(Collectors.toUnmodifiableSet());
        this.longestCommon = this.common.stream().mapToInt(String::length).max().orElse(0);
    }

    /** Throws {@link VaultException} ({@code password-rejected}) when the password breaks the rule. */
    void check(String password) {
        int length = password == null ? 0 : password.codePointCount(0, password.length());
        if (length < MIN_LENGTH) {
            throw VaultException.passwordRejected("The password needs at least " + MIN_LENGTH + " characters.");
        }
        if (length > MAX_LENGTH) {
            throw VaultException.passwordRejected("The password can have at most " + MAX_LENGTH + " characters.");
        }
        if (isCommon(password.toLowerCase(Locale.ROOT))) {
            throw VaultException.passwordRejected("That password is built from one of the most common ones (repeated "
                    + "or with digits or symbols added). Choose another.");
        }
    }

    /** Refuses a password that contains an account name or institution (at password change, database open). */
    void checkPersonal(String password, Collection<String> terms) {
        String flat = flatten(password);
        for (String term : terms) {
            String flatTerm = flatten(term);
            if (flatTerm.length() >= MIN_PERSONAL_TERM && flat.contains(flatTerm)) {
                throw VaultException.passwordRejected("That password contains the name of one of your accounts or "
                        + "banks. Choose another.");
            }
        }
    }

    /** Lower case, letters and digits only: "Apex Bank" and "apex-bank" are both "apexbank". */
    static String flatten(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder flat = new StringBuilder(text.length());
        text.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).forEach(flat::appendCodePoint);
        return flat.toString();
    }

    /** A common password once or repeated, with only digits or symbols before and after it. */
    boolean isCommon(String lower) {
        int first = firstLetter(lower);
        if (first < 0) {
            // No letter at all: padding and the core are both digits and symbols, so any position counts.
            return letterFree.stream().anyMatch(lower::contains);
        }
        int last = lastLetter(lower);
        // The core starts within the leading digits/symbols (a list word may start with a digit, like 1qaz2wsx) and
        // ends within the trailing ones.
        for (int start = 0; start <= first; start++) {
            for (int end = lower.length(); end > last; end--) {
                if (repeatsACommonWord(lower.substring(start, end))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean repeatsACommonWord(String core) {
        int length = core.length();
        for (int unit = 1; unit <= Math.min(length, longestCommon); unit++) {
            if (length % unit != 0) {
                continue;
            }
            String word = core.substring(0, unit);
            if (common.contains(word) && word.repeat(length / unit).equals(core)) {
                return true;
            }
        }
        return false;
    }

    private static int firstLetter(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int lastLetter(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (Character.isLetter(text.charAt(i))) {
                return i;
            }
        }
        return -1;
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
