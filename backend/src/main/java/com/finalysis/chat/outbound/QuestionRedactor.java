package com.finalysis.chat.outbound;

import com.finalysis.chat.ChatProperties;
import com.finalysis.chat.tool.MerchantDirectory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The outbound copy of the user's question (docs/plans/phase-6.md §12.4). In order:
 * <ol>
 *   <li>{@code < > { }} removed (the question goes inside {@code <question>} as a JSON string anyway);</li>
 *   <li>emails, URLs and phone numbers removed;</li>
 *   <li>a last four written as an identifier ({@code ending 2001}, {@code ending in 2001}, {@code card 2001},
 *       {@code account 2001}, {@code #2001}, {@code x2001}, {@code ***2001}) becomes its account's alias, or is removed
 *       when no account has it;</li>
 *   <li>any other run of five or more digits removed;</li>
 *   <li>a registered last four standing alone becomes the alias, unless it reads as a year (2000–2099);</li>
 *   <li>an account's name or institution, as a whole phrase, then each distinctive word of a name or an institution,
 *       becomes the alias of the account or accounts that have it ({@code CHECKING-1 or SAVINGS-1}): "my Summit card"
 *       and "Apex or Summit" name accounts by one word (step 8b). A distinctive word has three letters or more and is
 *       none of: a generic account word ({@link #GENERIC}: BANK, CARD, CHECKING, SAVINGS, ACCOUNT, ...), an everyday
 *       word ({@link #EVERYDAY}: DAY, MONTH, MY, ...), a word of a category name, or a word of a merchant the turn may
 *       name (such a word leaves the machine as a merchant anyway, and "Amazon" must stay a merchant when a card is
 *       called "Amazon Prime Visa"). The same alias written twice in a row ("rainy day" → one alias) is written
 *       once;</li>
 *   <li>a person-to-person counterparty becomes {@code [PERSON]}; a configured redact term {@code [NAME]}.</li>
 * </ol>
 * Amounts, merchants, months and categories are kept. Like the {@code Redactor}, it removes or replaces, never
 * rewrites, and the result reports counts only. Matching ignores case.
 */
@Component
public class QuestionRedactor {

    private static final Pattern MARKUP = Pattern.compile("[<>{}]");
    private static final Pattern EMAIL = Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern URL = Pattern.compile("(?i)\\bhttps?://\\S+|\\bwww\\.\\S+|\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)+/\\S*");
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)(?:\\+?1[ .-]?)?\\(?\\d{3}\\)?[ .-]?\\d{3}[ .-]?\\d{4}(?!\\d)");
    private static final Pattern KEYWORD_LAST_FOUR = Pattern.compile(
            "(?i)\\b(ending(?:\\s+in)?|card|account|acct|number|no\\.?)\\s*#?\\s*(\\d{4})(?!\\d)|#(\\d{4})(?!\\d)");
    private static final Pattern MASKED = Pattern.compile("(?i)(?<![a-z0-9])(?:x+|\\*+|\\.{3})(\\d{4,})(?!\\d)");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{5,}");
    private static final Pattern BARE_FOUR = Pattern.compile("(?<![\\d$.,/-])(\\d{4})(?![\\d]|[.,]\\d)");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9']+");

    /** Words of institutions that say nothing about whose account it is. */
    static final Set<String> GENERIC = Set.of("BANK", "CARD", "CHECKING", "SAVINGS", "SAVING", "VISA", "MASTERCARD",
            "AMEX", "CREDIT", "DEBIT", "UNION", "ACCOUNT", "ACCT", "THE", "AND", "FIRST", "NATIONAL", "FEDERAL",
            "TRUST", "JOINT", "PERSONAL", "BUSINESS", "PLUS", "REWARDS", "PLATINUM", "GOLD", "CASH", "BACK", "ONLINE",
            "FINANCIAL", "MONEY", "MARKET", "CO", "COMPANY", "INC", "SERVICES", "SERVICE", "GROUP", "HOLDINGS",
            "BANCORP", "FINANCE", "CORP", "CORPORATION", "ASSOCIATION", "ASSN", "LOAN");

    /**
     * Everyday words a user may give an account ("Rainy Day Savings", "Main Checking", "My Card") that a question uses
     * in their ordinary sense ("what day", "last month"): never matched alone, only inside the whole name.
     */
    static final Set<String> EVERYDAY = Set.of("DAY", "DAYS", "DAILY", "WEEK", "WEEKS", "WEEKLY", "MONTH", "MONTHS",
            "MONTHLY", "YEAR", "YEARS", "YEARLY", "TODAY", "NOW", "TIME", "MY", "MINE", "OUR", "OURS", "YOUR", "HIS",
            "HER", "THEIR", "FOR", "WITH", "FROM", "INTO", "OVER", "UNDER", "NOT", "ALL", "ANY", "SOME", "EACH", "ONE",
            "TWO", "NEW", "OLD", "MAIN", "OTHER", "SPARE", "EXTRA", "HOME", "HOUSE", "FAMILY", "KIDS", "BILLS", "BILL",
            "FUND", "FUNDS", "SPENDING", "SPEND", "SPENT", "PAY", "PAID", "BUY", "WHAT", "WHEN", "WHERE", "WHICH", "WHO",
            "HOW", "MUCH", "MANY", "MORE", "LESS", "MOST", "DID", "DOES", "WAS", "WERE", "HAVE", "HAS", "HAD", "GET",
            "GOT", "LAST", "NEXT", "THIS", "THAT", "THESE", "THOSE", "TOTAL", "BIG", "SMALL", "GOOD", "BEST",
            "EVERYTHING", "STUFF", "THINGS");
    private static final Pattern REPEATED_ALIAS = Pattern.compile(
            "(?<![A-Za-z0-9-])([A-Z][A-Z_]*-\\d+(?: or [A-Z][A-Z_]*-\\d+)*)(?:\\s+\\1)+(?![A-Za-z0-9-])");

    private final ChatPayloadGuard guard;
    private final ChatProperties properties;

    public QuestionRedactor(ChatPayloadGuard guard, ChatProperties properties) {
        this.guard = guard;
        this.properties = properties;
    }

    /**
     * What goes out instead of the question.
     *
     * @param text               the outbound text
     * @param aliasesUsed        account references replaced by an alias
     * @param identifiersRemoved emails, URLs, phone numbers, digit runs, unknown last fours, people and redact terms
     * @param sendable           the text passes the free-text check; when false, nothing is sent
     */
    public record OutboundQuestion(String text, int aliasesUsed, int identifiersRemoved, boolean sendable) {
    }

    public OutboundQuestion redact(String question, OutboundTurn turn) {
        Counts counts = new Counts();
        Map<String, String> aliasByLastFour = new LinkedHashMap<>();
        for (AccountIdentity identity : turn.identities()) {
            aliasByLastFour.merge(identity.lastFour(), turn.aliases().alias(identity.id()), (a, b) -> a + " or " + b);
        }

        String text = MARKUP.matcher(question).replaceAll(" ");
        text = remove(EMAIL, text, counts);
        text = remove(URL, text, counts);
        text = remove(PHONE, text, counts);
        text = replace(KEYWORD_LAST_FOUR, text, m -> {
            String digits = m.group(2) != null ? m.group(2) : m.group(3);
            String keyword = m.group(1) != null ? m.group(1) + " " : "";
            return lastFour(digits, aliasByLastFour, counts, keyword);
        });
        text = replace(MASKED, text, m -> lastFour(m.group(1).substring(m.group(1).length() - 4), aliasByLastFour,
                counts, ""));
        text = remove(LONG_DIGITS, text, counts);
        text = replace(BARE_FOUR, text, m -> {
            String digits = m.group(1);
            int value = Integer.parseInt(digits);
            if (!aliasByLastFour.containsKey(digits) || value >= 2000 && value <= 2099) {
                return digits;
            }
            counts.aliases++;
            return aliasByLastFour.get(digits);
        });
        for (Map.Entry<Pattern, String> phrase : accountPhrases(turn).entrySet()) {
            text = replace(phrase.getKey(), text, m -> {
                counts.aliases++;
                return phrase.getValue();
            });
        }
        text = REPEATED_ALIAS.matcher(text).replaceAll("$1");
        for (String counterparty : turn.redaction().counterparties()) {
            Pattern pattern = phrase(counterparty);
            if (pattern != null) {
                text = replace(pattern, text, m -> {
                    counts.identifiers++;
                    return "[PERSON]";
                });
            }
        }
        for (String term : properties.redactTerms()) {
            Pattern pattern = phrase(term);
            if (pattern != null) {
                text = replace(pattern, text, m -> {
                    counts.identifiers++;
                    return "[NAME]";
                });
            }
        }
        text = SPACES.matcher(text).replaceAll(" ").strip();
        return new OutboundQuestion(text, counts.aliases, counts.identifiers, guard.allowsText(text));
    }

    /**
     * Names and institutions as whole phrases first (longest first), then their distinctive words (see the class
     * comment), each mapped to the alias of every account that has it.
     */
    private static Map<Pattern, String> accountPhrases(OutboundTurn turn) {
        Map<String, Set<String>> phrases = new LinkedHashMap<>();
        Map<String, Set<String>> words = new LinkedHashMap<>();
        Set<String> notDistinctive = null;
        for (AccountIdentity identity : turn.identities()) {
            String alias = turn.aliases().alias(identity.id());
            for (String phrase : new String[] {identity.name(), identity.institution()}) {
                if (phrase == null || phrase.isBlank()) {
                    continue;
                }
                phrases.computeIfAbsent(phrase.strip().toUpperCase(Locale.ROOT), p -> new LinkedHashSet<>()).add(alias);
                Matcher word = WORD.matcher(phrase);
                while (word.find()) {
                    String w = word.group().toUpperCase(Locale.ROOT);
                    if (w.length() < 3 || GENERIC.contains(w) || EVERYDAY.contains(w)
                            || w.chars().noneMatch(Character::isLetter)) {
                        continue;
                    }
                    if (notDistinctive == null) {
                        notDistinctive = labelWords(turn);
                    }
                    if (!notDistinctive.contains(w)) {
                        words.computeIfAbsent(w, k -> new LinkedHashSet<>()).add(alias);
                    }
                }
            }
        }
        Map<Pattern, String> patterns = new LinkedHashMap<>();
        phrases.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Set<String>> e) -> e.getKey().length()).reversed())
                .forEach(e -> {
                    Pattern pattern = phrase(e.getKey());
                    if (pattern != null) {
                        patterns.put(pattern, String.join(" or ", e.getValue()));
                    }
                });
        words.forEach((word, aliases) -> patterns.put(phrase(word), String.join(" or ", aliases)));
        return patterns;
    }

    /** The words of category names and of the merchants the turn may name: labels, never an account by one word. */
    private static Set<String> labelWords(OutboundTurn turn) {
        Set<String> labels = new HashSet<>();
        turn.categories().keySet().forEach(name -> labels.addAll(MerchantDirectory.wordsOf(name)));
        turn.directory().texts().forEach(text -> labels.addAll(MerchantDirectory.wordsOf(text)));
        return labels;
    }

    private static String lastFour(String digits, Map<String, String> aliasByLastFour, Counts counts, String keyword) {
        String alias = aliasByLastFour.get(digits);
        if (alias == null) {
            counts.identifiers++;
            return keyword;
        }
        counts.aliases++;
        return keyword + alias;
    }

    /** A phrase's words in order, any non-alphanumeric run between them, whole words, ignoring case. */
    static Pattern phrase(String phrase) {
        List<String> words = new ArrayList<>();
        Matcher word = WORD.matcher(phrase);
        while (word.find()) {
            words.add(Pattern.quote(word.group()));
        }
        if (words.isEmpty()) {
            return null;
        }
        return Pattern.compile("(?i)(?<![A-Za-z0-9])" + String.join("[^A-Za-z0-9]+", words) + "(?![A-Za-z0-9])");
    }

    private static String remove(Pattern pattern, String text, Counts counts) {
        return replace(pattern, text, m -> {
            counts.identifiers++;
            return " ";
        });
    }

    private static String replace(Pattern pattern, String text, Function<Matcher, String> with) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(with.apply(m)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static final class Counts {
        int aliases;
        int identifiers;
    }
}
