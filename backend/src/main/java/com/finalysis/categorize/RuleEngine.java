package com.finalysis.categorize;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Matches txns against categorization rules and picks the winner (CAT-01; docs/plans/phase-4.md §3.2).
 * Plain Java with no Spring or database access: built once per pass from the active rules, then asked
 * about each txn. Not thread-safe (it counts regex timeouts); one pass uses one engine.
 *
 * <p>Text patterns ({@code EXACT}, {@code PREFIX}, {@code CONTAINS}) match whole words: an occurrence counts
 * only where it doesn't run into a letter or digit on either side, so {@code SHELL} matches
 * {@code SHELL OIL} but not {@code SHELLFISH SHACK}, and {@code ELECTRIC} doesn't match
 * {@code ELECTRONICS}. Regexes are matched with {@code Matcher.find()}, case-insensitively, on a
 * deadline: catastrophic backtracking ends in a skipped match, counted in {@link #timedOutRules()},
 * instead of a hung thread.
 */
public final class RuleEngine {

    /** Neither field is matched beyond this many characters, which bounds regex work. */
    static final int MAX_INPUT = 256;
    /** Longest pattern a rule may have (ck_categorization_rule_pattern_length). */
    public static final int MAX_PATTERN = 200;
    static final Duration REGEX_DEADLINE = Duration.ofMillis(50);
    static final int REGEX_FLAGS = Pattern.CASE_INSENSITIVE;

    /**
     * Which rule wins when several match (§3.2): user rules before seeds whatever their priorities (CAT-04),
     * then higher priority, then the more specific match type, then the longer pattern, then the lower id.
     */
    public static final Comparator<Rule> ORDER = Comparator
            .comparing((Rule rule) -> !rule.source().isUser())
            .thenComparing(Rule::priority, Comparator.reverseOrder())
            .thenComparing(Rule::matchType)
            .thenComparing(rule -> rule.pattern().length(), Comparator.reverseOrder())
            .thenComparingLong(Rule::id);

    /** A rule as the engine sees it. */
    public record Rule(long id, RuleField field, RuleMatchType matchType, String pattern, RuleDirection direction,
                       int priority, RuleSource source, long categoryId) {}

    /**
     * What rules are matched against: the cleaned merchant (may be null), the raw description normalized for
     * matching, and the amount (for the direction).
     */
    public record Subject(String merchant, String description, BigDecimal amount) {

        public static Subject of(String merchant, String rawDescription, BigDecimal amount) {
            return new Subject(cut(merchant), cut(rawDescription == null ? null : MerchantCleaner.normalize(rawDescription)),
                    amount);
        }

        String text(RuleField field) {
            return field == RuleField.MERCHANT ? merchant : description;
        }

        private static String cut(String text) {
            return text == null || text.length() <= MAX_INPUT ? text : text.substring(0, MAX_INPUT);
        }
    }

    private record Compiled(Rule rule, Pattern regex) {}

    private final List<Compiled> rules = new ArrayList<>();
    private final Set<Long> invalidRules = new LinkedHashSet<>();
    private final Set<Long> timedOutRules = new LinkedHashSet<>();
    private final long deadlineNanos;

    public RuleEngine(Collection<Rule> rules) {
        this(rules, REGEX_DEADLINE);
    }

    RuleEngine(Collection<Rule> rules, Duration regexDeadline) {
        this.deadlineNanos = regexDeadline.toNanos();
        for (Rule rule : rules.stream().sorted(ORDER).toList()) {
            if (rule.matchType() != RuleMatchType.REGEX) {
                this.rules.add(new Compiled(rule, null));
                continue;
            }
            try {
                this.rules.add(new Compiled(rule, compile(rule.pattern())));
            } catch (PatternSyntaxException e) {
                invalidRules.add(rule.id()); // only possible for a row written outside the API
            }
        }
    }

    /** Compiles a regex rule's pattern as the engine will match it; throws for an invalid one. */
    public static Pattern compile(String regex) {
        return Pattern.compile(regex, REGEX_FLAGS);
    }

    /** The rules this engine matches, in winning order (invalid regexes left out). */
    public List<Rule> rules() {
        return rules.stream().map(Compiled::rule).toList();
    }

    /** The highest-ranked rule that matches, if any. */
    public Optional<Rule> winner(Subject subject) {
        return winner(subject, rule -> true);
    }

    /**
     * The highest-ranked rule that matches among those {@code eligible} accepts; the others are skipped, so the next
     * one down can win.
     */
    public Optional<Rule> winner(Subject subject, Predicate<Rule> eligible) {
        for (Compiled compiled : rules) {
            if (eligible.test(compiled.rule()) && matches(compiled, subject)) {
                return Optional.of(compiled.rule());
            }
        }
        return Optional.empty();
    }

    /** Whether one of this engine's rules matches. */
    public boolean matches(long ruleId, Subject subject) {
        return rules.stream().filter(c -> c.rule().id() == ruleId).findFirst()
                .map(compiled -> matches(compiled, subject))
                .orElse(false);
    }

    /** Ids of stored regex rules that don't compile; they never match. */
    public Set<Long> invalidRules() {
        return Set.copyOf(invalidRules);
    }

    /** Ids of regex rules that ran past the deadline on some txn; that match was skipped. */
    public Set<Long> timedOutRules() {
        return Set.copyOf(timedOutRules);
    }

    private boolean matches(Compiled compiled, Subject subject) {
        Rule rule = compiled.rule();
        String text = subject.text(rule.field());
        if (text == null || !rule.direction().matches(subject.amount())) {
            return false;
        }
        return switch (rule.matchType()) {
            case EXACT -> text.equals(rule.pattern());
            case PREFIX -> text.startsWith(rule.pattern()) && wholeWord(text, 0, rule.pattern());
            case CONTAINS -> contains(text, rule.pattern());
            case REGEX -> find(compiled, text);
        };
    }

    private static boolean contains(String text, String pattern) {
        for (int at = text.indexOf(pattern); at >= 0; at = text.indexOf(pattern, at + 1)) {
            if (wholeWord(text, at, pattern)) {
                return true;
            }
        }
        return false;
    }

    /** The occurrence at {@code start} doesn't run into a letter or digit on either side. */
    private static boolean wholeWord(String text, int start, String pattern) {
        int end = start + pattern.length();
        boolean leftOpen = start == 0 || !joins(text.charAt(start - 1), pattern.charAt(0));
        boolean rightOpen = end == text.length() || !joins(pattern.charAt(pattern.length() - 1), text.charAt(end));
        return leftOpen && rightOpen;
    }

    private static boolean joins(char a, char b) {
        return Character.isLetterOrDigit(a) && Character.isLetterOrDigit(b);
    }

    private boolean find(Compiled compiled, String text) {
        try {
            return compiled.regex().matcher(new DeadlineCharSequence(text, System.nanoTime() + deadlineNanos)).find();
        } catch (DeadlineExceeded e) {
            timedOutRules.add(compiled.rule().id());
            return false;
        }
    }

    /**
     * Input that throws once its deadline passes; the regex matcher reads every character through it. The
     * clock is read every {@link #CHECK_EVERY} reads, not on each one.
     */
    private static final class DeadlineCharSequence implements CharSequence {

        private static final int CHECK_EVERY = 64;

        private final CharSequence text;
        private final long deadline;
        private int reads;

        DeadlineCharSequence(CharSequence text, long deadline) {
            this.text = text;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if (++reads % CHECK_EVERY == 0 && System.nanoTime() > deadline) {
                throw DeadlineExceeded.INSTANCE;
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(text.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    /** Control flow only: no stack trace, never escapes this class. */
    private static final class DeadlineExceeded extends RuntimeException {
        static final DeadlineExceeded INSTANCE = new DeadlineExceeded();

        private DeadlineExceeded() {
            super(null, null, false, false);
        }
    }
}
