package com.finalysis.categorize;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Removes identifiers from every merchant headed for a model (SEC-08, docs/plans/phase-4.md §5.1, §5.2): each
 * request item's merchant, each similar-merchant example's merchant, and the embedding text, all through
 * {@link #redact}. It runs after cleaning and before {@link OutboundPayloadGuard}, on a copy: stored merchants,
 * rules and the UI never see its output.
 *
 * <p>Separate from cleaning on purpose, so a cleaning change can't weaken what leaves the machine. In order:
 * <ol>
 *   <li>the file's {@code redactPatterns} (ACH names and ids, confirmation, reference and trace numbers);</li>
 *   <li>emails, URLs with a path, and phone numbers;</li>
 *   <li>masked numbers ({@code XX1002}, {@code *1002}, {@code ...1002});</li>
 *   <li>the last four of every registered account, as a separate token;</li>
 *   <li>any run of four or more digits;</li>
 *   <li>account and institution names, as whole phrases;</li>
 *   <li>the location tail: a trailing state code, then one city token, then one multi-word city prefix
 *       ({@code locationCityPrefixes}), each only while a token remains. Aggressive on purpose: over-stripping
 *       costs some accuracy, never privacy.</li>
 * </ol>
 * It removes, never rewrites: a character the rules don't expect stays inside its word, and the guard refuses
 * the string (only words without a letter or digit are dropped). A person-to-person payment
 * ({@link #isPersonToPerson}) is never sent at all.
 */
@Component
public class Redactor {

    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+");
    private static final Pattern URL = Pattern.compile("\\bHTTPS?://\\S*|(?:[A-Z0-9-]+\\.)+[A-Z]{2,}/\\S*");
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)(?:\\+?1[ .-]?)?\\(?\\d{3}\\)?[ .-]?\\d{3}[ .-]?\\d{4}(?!\\d)");
    private static final Pattern MASKED_NUMBER = Pattern.compile("(?:X{2,}|\\*+|\\.{3})\\d+");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{4,}");
    private static final Pattern WORDS = Pattern.compile("[^A-Z0-9]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final String EDGE_PUNCTUATION = "&'./- ";

    private final MerchantCleaningRules rules;

    public Redactor(MerchantCleaningRules rules) {
        this.rules = rules;
    }

    /**
     * The outbound copy of a merchant, or null when nothing is left. Input is normalized as descriptions are
     * (upper case, accents removed, single spaces), so a merchant from any source gets the same treatment.
     */
    public String redact(String merchant, RedactionContext context) {
        if (merchant == null) {
            return null;
        }
        String text = MerchantCleaner.normalize(merchant);
        for (Pattern pattern : rules.redactPatterns()) {
            text = pattern.matcher(text).replaceAll(" ");
        }
        text = EMAIL.matcher(text).replaceAll(" ");
        text = URL.matcher(text).replaceAll(" ");
        text = PHONE.matcher(text).replaceAll(" ");
        text = MASKED_NUMBER.matcher(text).replaceAll(" ");
        for (String lastFour : context.lastFours()) {
            text = token(lastFour).matcher(text).replaceAll(" ");
        }
        text = LONG_DIGITS.matcher(text).replaceAll(" ");
        for (String phrase : longestFirst(context.accountPhrases())) {
            Pattern pattern = phrase(phrase);
            if (pattern != null) {
                text = pattern.matcher(text).replaceAll(" ");
            }
        }
        List<String> tokens = new ArrayList<>(Arrays.asList(trimEdges(SPACES.matcher(text).replaceAll(" ")).split(" ")));
        tokens.removeIf(token -> token.chars().noneMatch(Character::isLetterOrDigit));
        String redacted = trimEdges(String.join(" ", withoutLocation(tokens)));
        return redacted.isEmpty() ? null : redacted;
    }

    /**
     * A person-to-person payment, never sent (§5.2): it has a counterparty, or its merchant matches one of the
     * file's {@code personToPerson} patterns (cleaning leaves the service's name as the merchant).
     */
    public boolean isPersonToPerson(String merchant, String counterparty) {
        if (counterparty != null && !counterparty.isBlank()) {
            return true;
        }
        if (merchant == null) {
            return false;
        }
        String text = MerchantCleaner.normalize(merchant);
        return rules.personToPerson().stream().anyMatch(pattern -> pattern.matcher(text).find());
    }

    /**
     * A phrase as a whole-word pattern over normalized text: its words in order, any non-alphanumeric run
     * between them ({@code JOINT CHECKING - SAMPLE} matches {@code JOINT CHECKING SAMPLE}). Null for a phrase
     * with no letter or digit.
     */
    static Pattern phrase(String phrase) {
        List<String> words = Arrays.stream(WORDS.split(MerchantCleaner.normalize(phrase)))
                .filter(word -> !word.isEmpty())
                .map(Pattern::quote)
                .toList();
        if (words.isEmpty()) {
            return null;
        }
        return Pattern.compile("(?<![A-Z0-9])" + String.join("[^A-Z0-9]+", words) + "(?![A-Z0-9])");
    }

    /** §5.2's three steps, on space-separated tokens; never empties the merchant. */
    private List<String> withoutLocation(List<String> tokens) {
        List<String> kept = new ArrayList<>(tokens);
        if (kept.size() < 2 || !rules.locationStates().contains(kept.getLast())) {
            return kept;
        }
        kept.removeLast(); // the state code
        if (kept.size() < 2) {
            return kept;
        }
        kept.removeLast(); // one city token
        if (kept.size() >= 2 && rules.locationCityPrefixes().contains(kept.getLast())) {
            kept.removeLast(); // at most one multi-word city prefix
        }
        return kept;
    }

    private static Pattern token(String value) {
        return Pattern.compile("(?<![A-Z0-9])" + Pattern.quote(value) + "(?![A-Z0-9])");
    }

    private static List<String> longestFirst(List<String> phrases) {
        return phrases.stream().sorted((a, b) -> Integer.compare(b.length(), a.length())).toList();
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
}
