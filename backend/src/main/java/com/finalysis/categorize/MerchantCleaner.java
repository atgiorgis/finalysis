package com.finalysis.categorize;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns a raw description into a cleaned merchant name (DAT-04; docs/plans/phase-4.md §2), and a
 * person-to-person payment's payee or payer into a counterparty.
 *
 * <p>Generic code only: every processor, ACH, bank or country pattern comes from
 * {@link MerchantCleaningRules}, so this class holds no such strings ({@code MerchantCleanerSourceTest}).
 * The raw description is only read; it stays unchanged in the database and in the fingerprint.
 *
 * <p>Deterministic and idempotent: cleaning a cleaned merchant gives it back.
 */
@Component
public class MerchantCleaner {

    /** Longest merchant kept, cut at a word boundary. */
    public static final int MAX_MERCHANT = 64;
    /** Longest counterparty kept. */
    public static final int MAX_COUNTERPARTY = 100;
    /** Patterns never see more than this, which bounds their work on hostile input. */
    static final int MAX_INPUT = 512;
    private static final int MAX_PREFIX_ROUNDS = 10;

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern LINE_BREAKS_AND_TABS = Pattern.compile("[\\r\\n\\t\\u000B\\f\\u0085\\u2028\\u2029]");
    private static final Pattern CONTROL_AND_FORMAT = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /** Masked account numbers: XXXXXX1002, ****1002, ...1002. */
    private static final Pattern MASKED_NUMBER = Pattern.compile("(?<![A-Z0-9])(?:X{2,}|\\*{2,}|\\.{3})\\d+");
    private static final Pattern NOT_KEPT = Pattern.compile("[^A-Z0-9&'./ -]");
    private static final Pattern ORDINAL = Pattern.compile("\\d+(?:ST|ND|RD|TH)");
    private static final String EDGE_PUNCTUATION = "&'./- ";

    private final MerchantCleaningRules rules;

    public MerchantCleaner(MerchantCleaningRules rules) {
        this.rules = rules;
    }

    public MerchantCleaningRules rules() {
        return rules;
    }

    /** @return the cleaned merchant and counterparty; either may be null */
    public CleanedMerchant clean(String rawDescription) {
        if (rawDescription == null) {
            return CleanedMerchant.NONE;
        }
        String text = normalize(rawDescription);

        for (Pattern p2p : rules.personToPerson()) {
            Matcher m = p2p.matcher(text);
            if (m.find()) {
                String name = p2p.namedGroups().containsKey("name") ? m.group("name") : null;
                return new CleanedMerchant(finish(tokens(m.group("service"))), counterparty(name));
            }
        }

        text = stripPrefixes(text);
        for (Pattern strip : rules.stripPatterns()) {
            text = strip.matcher(text).replaceAll(" ");
        }
        text = MASKED_NUMBER.matcher(text).replaceAll(" ");
        return new CleanedMerchant(finish(withoutIds(withoutLocation(tokens(text)))), null);
    }

    /**
     * Wrapped lines joined with a space; compatibility forms and accents folded (fullwidth letters,
     * CAFÉ → CAFE); curly apostrophes made straight; control and format characters (bidi overrides
     * included) removed; upper case; single spaces.
     */
    static String normalize(String raw) {
        String text = LINE_BREAKS_AND_TABS.matcher(raw).replaceAll(" ");
        text = Normalizer.normalize(text, Normalizer.Form.NFKD);
        text = MARKS.matcher(text).replaceAll("");
        text = text.replace('‘', '\'').replace('’', '\'');
        text = CONTROL_AND_FORMAT.matcher(text).replaceAll("");
        text = SPACES.matcher(text.toUpperCase(Locale.ROOT)).replaceAll(" ").strip();
        return text.length() > MAX_INPUT ? text.substring(0, MAX_INPUT) : text;
    }

    private String stripPrefixes(String text) {
        for (int round = 0; round < MAX_PREFIX_ROUNDS; round++) {
            boolean stripped = false;
            for (Pattern prefix : rules.stripPrefixes()) {
                Matcher m = prefix.matcher(text);
                if (m.lookingAt() && m.end() > 0) {
                    text = text.substring(m.end()).strip();
                    stripped = true;
                }
            }
            if (!stripped) {
                break;
            }
        }
        return text;
    }

    /** A token, and whether a {@code *} came right before it (a processor's reference separator). */
    private record Token(String text, boolean afterStar) {}

    private static List<Token> tokens(String text) {
        List<Token> tokens = new ArrayList<>();
        boolean afterStar = false;
        for (String part : text.replace("*", " * ").split(" ")) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.equals("*")) {
                afterStar = true;
                continue;
            }
            tokens.add(new Token(part, afterStar));
            afterStar = false;
        }
        return tokens;
    }

    /**
     * A description ending in a state code loses the tail from its last store number on:
     * {@code SHELL OIL 57442 ARLINGTON VA} → {@code SHELL OIL}. Without a store number the city's
     * extent is unknown, so nothing is cut.
     */
    private List<Token> withoutLocation(List<Token> tokens) {
        if (tokens.size() < 3 || !rules.locationStates().contains(tokens.getLast().text())) {
            return tokens;
        }
        for (int i = tokens.size() - 2; i > 0; i--) {
            if (hasDigit(tokens.get(i).text())) {
                return tokens.subList(0, i);
            }
        }
        return tokens;
    }

    /**
     * Drops store numbers and reference ids: a token with a digit that starts with {@code #}, has at
     * least as many digits as letters (and 3+ characters), or follows a {@code *}. Keeps names such as
     * {@code 7-ELEVEN} and ordinals such as {@code 21ST}.
     */
    private static List<Token> withoutIds(List<Token> tokens) {
        return tokens.stream().filter(t -> !isId(t)).toList();
    }

    private static boolean isId(Token token) {
        String text = token.text();
        if (!hasDigit(text) || ORDINAL.matcher(text).matches()) {
            return false;
        }
        long digits = text.chars().filter(Character::isDigit).count();
        long letters = text.chars().filter(Character::isLetter).count();
        return text.startsWith("#") || token.afterStar() || (digits >= letters && text.length() >= 3);
    }

    /** Keeps letters, digits and {@code & ' . / -}; drops punctuation-only tokens; cuts to 64 at a word. */
    private static String finish(List<Token> tokens) {
        List<String> words = new ArrayList<>();
        for (Token token : tokens) {
            for (String word : NOT_KEPT.matcher(token.text()).replaceAll(" ").split(" ")) {
                if (word.chars().anyMatch(Character::isLetterOrDigit)) {
                    words.add(word);
                }
            }
        }
        String merchant = trimEdges(String.join(" ", words));
        if (merchant.length() > MAX_MERCHANT) {
            int cut = merchant.lastIndexOf(' ', MAX_MERCHANT);
            merchant = trimEdges(cut > 0 ? merchant.substring(0, cut) : merchant.substring(0, MAX_MERCHANT));
        }
        return merchant.isEmpty() ? null : merchant;
    }

    private static String counterparty(String name) {
        if (name == null) {
            return null;
        }
        String cleaned = trimEdges(SPACES.matcher(name).replaceAll(" "));
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_COUNTERPARTY ? cleaned.substring(0, MAX_COUNTERPARTY).strip() : cleaned;
    }

    private static String trimEdges(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && EDGE_PUNCTUATION.indexOf(text.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && EDGE_PUNCTUATION.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean hasDigit(String text) {
        return text.chars().anyMatch(Character::isDigit);
    }

    /** The result of cleaning one description. */
    public record CleanedMerchant(String merchant, String counterparty) {
        static final CleanedMerchant NONE = new CleanedMerchant(null, null);
    }
}
