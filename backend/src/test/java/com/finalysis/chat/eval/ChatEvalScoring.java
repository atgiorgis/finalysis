package com.finalysis.chat.eval;

import com.finalysis.chat.AnswerSource;
import com.finalysis.chat.LimitHit;
import com.finalysis.chat.eval.ChatEvalSet.Expect;
import com.finalysis.chat.eval.ChatEvalSet.ExpectedFigure;
import com.finalysis.chat.eval.ChatEvalSet.FigureKind;
import com.finalysis.chat.eval.ChatEvalSet.Question;
import com.finalysis.chat.eval.ChatEvalSet.Turn;
import com.finalysis.chat.grounding.AnswerGrounding;
import com.finalysis.chat.grounding.Figure;
import com.finalysis.chat.grounding.FigureExtractor;
import com.finalysis.chat.grounding.GroundedSet;
import com.finalysis.chat.grounding.GroundingMatcher;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Deterministic scoring of chat answers (docs/plans/phase-6.md §13.3, step 8): no model judges anything.
 * <ul>
 *   <li><b>An answer turn</b> is correct when the turn shows an answer, every figure of at least one listed
 *       alternative appears in it, and no forbidden figure does. "Appears" means a figure that
 *       {@link FigureExtractor} finds in the shown text matches the expected value by the grounding rule itself
 *       ({@link GroundingMatcher}: exact, or whole dollars and whole percents rounded half-even). Every figure of a shown
 *       answer is grounded by construction (the model's answer passed the check, or it is the Java fallback), so a
 *       figure that appears is a grounded one. The whole numbers 0 and 1 are never figures to the extractor (§5.2), so
 *       an expected count of 0 or 1 is looked for as a digit or a word ("1", "one", "a single"; "0", "zero", "no",
 *       "none").</li>
 *   <li><b>A refusal turn</b> is correct only when the shown answer has no figure at all and reads as a refusal
 *       ({@link #isRefusal}). The system prompt prescribes no fixed phrase, so a refusal is any of a fixed list of
 *       wordings ("can't tell", "don't have", "no data", "only answer questions about", ...), with no figure.</li>
 *   <li><b>Phrase checks</b> (step 8d), opt-in per turn, where the figures can be right and the meaning wrong ("February
 *       was higher" with both months' totals): at least one {@code says} expression must match the shown answer, and
 *       no {@code neverSays} expression may.</li>
 *   <li><b>Limit-stopped turns</b> (step 8d): a turn a limit stopped is never a correct refusal, and one stopped before
 *       any tool returned data is never correct at all; both are reported as limit-stopped.</li>
 *   <li><b>Scoring notes</b> where figures alone can't decide ({@link #READINGS}): a dialogue whose turns accept
 *       several readings of a word must keep one reading across its turns, unless the first turn gave several or asked
 *       which one was meant (then a clarifying question also counts).</li>
 * </ul>
 * Tool choice is scored apart from correctness: the turn's calls include every tool of at least one alternative (order
 * ignored, extra calls allowed; an empty alternative needs none).
 */
final class ChatEvalScoring {

    /**
     * Readings of a dialogue's figure alternatives, by question id: each group is the alternatives (by index, the same
     * in every turn) that belong to one reading. From the plan's notes, not from tuning: h-u07 (step 1 notes, revision
     * 1), "save" read as Phase 5's net cash flow or savings rate (alternatives 0 and 1), or as the amount moved to
     * savings (alternative 2).
     */
    static final Map<String, List<Set<Integer>>> READINGS = Map.of("h-u07", List.of(Set.of(0, 1), Set.of(2)));

    private static final Pattern REFUSAL = Pattern.compile("(?i)\\b(?:can't|cannot|can not|couldn't|could not|"
            + "unable|not able|don't have|do not have|doesn't (?:have|include|contain|show|cover)|"
            + "does not (?:have|include|contain|show|cover)|isn't (?:in|available|something|possible)|"
            + "is not (?:in|available|something|possible)|not available|no (?:data|transactions|records?|information|"
            + "such|way)|there (?:is|are) no|only (?:answer|help with)|not possible|won't be able)\\b");
    private static final Pattern MARKERS_AND_ALIASES = Pattern.compile("\\[[^\\]]*]|\\b[A-Z][A-Z_]*-\\d+\\b");
    private static final Pattern ONE = Pattern.compile("(?i)(?<![\\d.,$])1(?![\\d.,%])|\\bone\\b|\\ba single\\b");
    private static final Pattern ZERO = Pattern.compile("(?i)(?<![\\d.,$])0(?![\\d.,%])|\\bzero\\b|\\bno\\b|\\bnone\\b");
    private static final Pattern BARE_YEAR = Pattern.compile("20\\d{2}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ChatEvalScoring() {
    }

    /**
     * What one turn showed.
     *
     * @param answer         the shown text, or null when the turn failed (a model error or a refused request)
     * @param tools          the names of the tools the turn called (an invented name is not one)
     * @param citationsFound whether every expected citation is among the turn's; null when none is expected
     * @param limit          the first limit the turn reached, or null
     * @param data           whether any tool call of the turn returned data ({@code OK})
     */
    record Shown(String answer, AnswerSource source, Set<String> tools, Boolean citationsFound, LimitHit limit,
                 boolean data) {

        Shown {
            tools = Set.copyOf(tools);
        }

        /** A turn no limit stopped. */
        Shown(String answer, AnswerSource source, Set<String> tools, Boolean citationsFound) {
            this(answer, source, tools, citationsFound, null, true);
        }
    }

    /**
     * One turn, scored.
     *
     * @param matched    the figure alternatives fully present, by index
     * @param figures    every figure found in the shown answer, as written
     * @param forbidden  the forbidden figures that appeared
     * @param clarifying no figure and a question mark: the answer asks something back
     * @param limitStopped a limit stopped the turn, and it expected a refusal or got no data (never correct)
     */
    record TurnScore(boolean correct, String reason, List<Integer> matched, List<String> figures,
                     List<String> forbidden, boolean refusal, boolean clarifying, boolean toolChoice,
                     boolean limitStopped) {

        TurnScore {
            matched = List.copyOf(matched);
            figures = List.copyOf(figures);
            forbidden = List.copyOf(forbidden);
        }

        TurnScore(boolean correct, String reason, List<Integer> matched, List<String> figures,
                  List<String> forbidden, boolean refusal, boolean clarifying, boolean toolChoice) {
            this(correct, reason, matched, figures, forbidden, refusal, clarifying, toolChoice, false);
        }

        TurnScore with(boolean correct, String reason) {
            return new TurnScore(correct, reason, matched, figures, forbidden, refusal, clarifying, toolChoice,
                    limitStopped);
        }
    }

    /** Every turn of a question, in order, with its scoring note applied. */
    static List<TurnScore> score(Question question, List<Shown> shown, Collection<String> labels) {
        if (shown.size() > question.turns().size()) {
            throw new IllegalArgumentException(question.id() + " has " + question.turns().size() + " turns");
        }
        List<TurnScore> scores = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            scores.add(score(question.turns().get(i), shown.get(i), labels));
        }
        List<Set<Integer>> readings = READINGS.get(question.id());
        return readings == null ? scores : keepOneReading(readings, scores, shown);
    }

    /** One turn on its own, without a scoring note. */
    static TurnScore score(Turn turn, Shown shown, Collection<String> labels) {
        boolean toolChoice = toolChoice(turn.tools(), shown.tools());
        if (shown.answer() == null) {
            return new TurnScore(false, "no answer (" + shown.source() + ")", List.of(), List.of(), List.of(), false,
                    false, toolChoice);
        }
        String text = shown.answer();
        List<Figure> figures = FigureExtractor.extract(text, labels);
        List<String> written = figures.stream().map(Figure::asWritten).toList();
        List<String> forbidden = turn.forbidden().stream().filter(f -> present(f, text, figures))
                .map(ExpectedFigure::text).toList();
        List<Integer> matched = IntStream.range(0, turn.figures().size())
                .filter(i -> turn.figures().get(i).stream().allMatch(f -> present(f, text, figures)))
                .boxed().toList();
        boolean refusal = figures.isEmpty() && isRefusal(text);
        boolean clarifying = figures.isEmpty() && text.contains("?");

        if (shown.limit() != null && (turn.expect() == Expect.REFUSAL || !shown.data())) {
            return new TurnScore(false, "limit-stopped (" + shown.limit() + ")", matched, written, forbidden, refusal,
                    clarifying, toolChoice, true);
        }
        if (turn.expect() == Expect.REFUSAL) {
            if (!figures.isEmpty()) {
                return new TurnScore(false, "a refusal was expected; the answer has figures " + written, matched,
                        written, forbidden, false, false, toolChoice);
            }
            return new TurnScore(refusal, refusal ? "refused" : "a refusal was expected; the answer doesn't refuse",
                    matched, written, forbidden, refusal, clarifying, toolChoice);
        }
        if (!forbidden.isEmpty()) {
            return new TurnScore(false, "forbidden figure " + forbidden, matched, written, forbidden, refusal,
                    clarifying, toolChoice);
        }
        if (!turn.figures().isEmpty() && matched.isEmpty()) {
            return new TurnScore(false, (refusal ? "refused; " : "") + "missing figures: expected one of "
                    + turn.figures().stream().map(a -> a.stream().map(ExpectedFigure::text).toList()).toList()
                    + ", found " + written, matched, written, forbidden, refusal, clarifying, toolChoice);
        }
        String phrases = phraseProblem(turn, text);
        if (phrases != null) {
            return new TurnScore(false, phrases, matched, written, forbidden, refusal, clarifying, toolChoice);
        }
        return new TurnScore(true, turn.figures().isEmpty() ? "answered (no figure expected)" : "correct", matched,
                written, forbidden, refusal, clarifying, toolChoice);
    }

    /** Why the answer fails the turn's phrase checks, or null when it passes (or has none). */
    static String phraseProblem(Turn turn, String answer) {
        String text = WHITESPACE.matcher(answer.replace('’', '\'')).replaceAll(" ");
        if (!turn.says().isEmpty() && turn.says().stream().noneMatch(p -> p.matcher(text).find())) {
            return "says none of " + turn.says().stream().map(Pattern::pattern).toList();
        }
        List<String> said = turn.neverSays().stream().filter(p -> p.matcher(text).find()).map(Pattern::pattern)
                .toList();
        return said.isEmpty() ? null : "says what it must not: " + said;
    }

    /**
     * The reading rule: the first turn's reading (the groups of its matched alternatives) must hold in later turns. A
     * first turn that gave several readings, or asked which one was meant, leaves the later turns free.
     */
    private static List<TurnScore> keepOneReading(List<Set<Integer>> readings, List<TurnScore> scores,
                                                  List<Shown> shown) {
        if (scores.isEmpty()) {
            return scores;
        }
        List<TurnScore> kept = new ArrayList<>(scores);
        TurnScore first = scores.getFirst();
        boolean asked = !first.correct() && first.clarifying() && first.forbidden().isEmpty()
                && shown.getFirst().answer() != null;
        if (asked) {
            kept.set(0, first.with(true, "asked which reading is meant"));
        }
        Set<Integer> used = new TreeSet<>();
        for (int alternative : first.matched()) {
            for (int g = 0; g < readings.size(); g++) {
                if (readings.get(g).contains(alternative)) {
                    used.add(g);
                }
            }
        }
        if (asked || used.size() != 1) {
            return kept;
        }
        Set<Integer> reading = readings.get(used.iterator().next());
        for (int i = 1; i < scores.size(); i++) {
            TurnScore turn = scores.get(i);
            if (turn.correct() && turn.matched().stream().noneMatch(reading::contains)) {
                kept.set(i, turn.with(false, "a different reading than the first turn's (alternatives "
                        + turn.matched() + ", first turn " + first.matched() + ")"));
            }
        }
        return kept;
    }

    /** Whether the expected figure appears in the answer (see the class comment). */
    static boolean present(ExpectedFigure expected, String text, List<Figure> figures) {
        if (expected.kind() == FigureKind.COUNT && expected.value().compareTo(BigDecimal.ONE) <= 0) {
            String plain = MARKERS_AND_ALIASES.matcher(text).replaceAll(" ");
            return (expected.value().signum() == 0 ? ZERO : ONE).matcher(plain).find();
        }
        GroundedSet.Value value = new GroundedSet.Value(switch (expected.kind()) {
            case MONEY -> GroundedSet.Kind.MONEY;
            case PERCENT -> GroundedSet.Kind.PERCENT;
            case COUNT -> GroundedSet.Kind.COUNT;
        }, expected.value(), expected.text(), null, null, 0);
        return figures.stream().anyMatch(figure -> GroundingMatcher.matches(figure, value));
    }

    /** Refusal wording (see the class comment); the caller also requires that the answer has no figure. */
    static boolean isRefusal(String text) {
        return REFUSAL.matcher(text.replace('’', '\'')).find();
    }

    static boolean toolChoice(List<Set<String>> alternatives, Set<String> called) {
        return alternatives.isEmpty() || alternatives.stream().anyMatch(called::containsAll);
    }

    /** The figures a grounding retry listed, as written; empty when the message is not the retry. */
    static List<String> retryFigures(String message) {
        if (message == null || !message.startsWith(AnswerGrounding.RETRY_PREFIX)) {
            return List.of();
        }
        String rest = message.substring(AnswerGrounding.RETRY_PREFIX.length());
        int end = rest.indexOf(". Rewrite");
        return Arrays.stream((end < 0 ? rest : rest.substring(0, end)).split(", ")).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
    }

    /** A bare year (§15): a whole number 2000 to 2099 written alone, which the extractor counts as a figure. */
    static boolean bareYear(String asWritten) {
        return BARE_YEAR.matcher(asWritten).matches();
    }
}
