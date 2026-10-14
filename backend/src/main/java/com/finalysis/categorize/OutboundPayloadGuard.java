package com.finalysis.categorize;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The last check before anything leaves the machine (SEC-01, SEC-08; docs/plans/phase-4.md §5.3): an
 * allowlist that fails closed. It only answers yes or no and never repairs; a merchant that fails is not sent.
 *
 * <p>A merchant may be sent only when it is 1–64 characters of {@code A–Z 0–9 & ' . / -} and spaces, has no
 * run of four or more digits, has none of {@code @ : < > { } " \}, and contains no txn's counterparty as a
 * whole phrase. An amount must look like {@code -25.67}; a key like {@code i1}; a category name is 1–64
 * letters, digits, spaces and {@code & ' . / ( ) -}.
 */
@Component
public class OutboundPayloadGuard {

    static final int MAX_MERCHANT = 64;
    private static final Pattern ALLOWED = Pattern.compile("[A-Z0-9&'./ -]{1," + MAX_MERCHANT + "}");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{4,}");
    private static final Pattern FORBIDDEN = Pattern.compile("[@:<>{}\"\\\\]");
    private static final Pattern AMOUNT = Pattern.compile("-?\\d{1,10}\\.\\d{2}");
    private static final Pattern KEY = Pattern.compile("i[1-9]\\d{0,3}");
    private static final Pattern CATEGORY = Pattern.compile("[A-Za-z0-9&'./ ()-]{1,64}");

    /** True when this (already redacted) merchant may be sent. */
    public boolean allowsMerchant(String merchant, RedactionContext context) {
        if (merchant == null || merchant.isBlank() || !ALLOWED.matcher(merchant).matches()
                || LONG_DIGITS.matcher(merchant).find() || FORBIDDEN.matcher(merchant).find()) {
            return false;
        }
        for (String counterparty : context.counterparties()) {
            Pattern phrase = Redactor.phrase(counterparty);
            if (phrase != null && phrase.matcher(merchant).find()) {
                return false;
            }
        }
        return true;
    }

    public boolean allowsAmount(String amount) {
        return amount != null && AMOUNT.matcher(amount).matches();
    }

    /** A category name: public, seeded data, but checked too, so nothing unchecked reaches the prompt. */
    public boolean allowsCategory(String category) {
        return category != null && CATEGORY.matcher(category).matches();
    }

    public boolean allowsKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }
}
