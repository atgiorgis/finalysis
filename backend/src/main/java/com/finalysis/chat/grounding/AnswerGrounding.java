package com.finalysis.chat.grounding;

import com.finalysis.chat.outbound.ChatPayloadGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Number grounding (CHT-04, docs/plans/phase-6.md §5): every figure in an answer must match a figure in that turn's
 * tool results. In order: the answer is made plain text ({@link AnswerText}), markers this turn never issued are
 * removed ({@link CitationMarkers}), then every figure is found ({@link FigureExtractor}) and matched against the
 * grounded set ({@link GroundedSet}, {@link GroundingMatcher}). What is checked is exactly what would be shown.
 *
 * <p>The loop (step 5) uses the three methods in turn: {@link #check}; on an ungrounded answer one retry with
 * {@link #retryMessage}; if the retry is still ungrounded (or a limit was hit), {@link FallbackAnswer#render} instead,
 * and the model's text is discarded.
 */
@Component
public class AnswerGrounding {

    private static final Pattern SENTENCE_END = Pattern.compile("[.!?](?=\\s|$)|\\R");

    private final ChatPayloadGuard guard;

    public AnswerGrounding(ChatPayloadGuard guard) {
        this.guard = guard;
    }

    public AnswerCheck check(String answer, TurnEvidence evidence) {
        AnswerText.Plain plain = AnswerText.plain(Objects.requireNonNullElse(answer, ""));
        CitationMarkers.Parsed parsed = CitationMarkers.parse(plain.text(), evidence.refs()::issued);
        GroundedSet set = GroundedSet.of(evidence, guard);
        String text = parsed.text();
        List<Integer> ends = sentenceEnds(text);

        List<AnswerCheck.FigureMatch> matches = new ArrayList<>();
        List<Figure> ungrounded = new ArrayList<>();
        for (Figure figure : FigureExtractor.extract(text, set.labels())) {
            List<GroundedSet.Value> values = GroundingMatcher.matches(figure, set);
            if (values.isEmpty()) {
                ungrounded.add(figure);
            }
            int sentence = sentence(ends, figure.start());
            Set<String> marked = parsed.markers().stream().filter(m -> sentence(ends, m.start()) == sentence)
                    .map(CitationMarkers.Marker::ref).collect(Collectors.toSet());
            matches.add(new AnswerCheck.FigureMatch(figure, values, cited(figure, values, marked)));
        }
        return new AnswerCheck(text, parsed.markers(), parsed.dropped(), plain.removed(), matches, ungrounded);
    }

    /**
     * The one retry's message (§5.5): fixed text and the ungrounded figures as written (each only its own characters:
     * digits, separators, {@code $}, {@code %}, a sign).
     */
    public String retryMessage(AnswerCheck check) {
        if (check.grounded()) {
            throw new IllegalArgumentException("the answer is grounded; there is nothing to retry");
        }
        return "These figures are not in this turn's tool results: "
                + check.ungrounded().stream().map(Figure::asWritten).distinct().collect(Collectors.joining(", "))
                + ". Rewrite the answer using only figures from tool results, call a tool if one returns the figure "
                + "you need (`compare_periods` for differences), or say you can't tell.";
    }

    /** The values a figure cites: see {@link AnswerCheck.FigureMatch}. Values under no ref never cite. */
    static List<GroundedSet.Value> cited(Figure figure, List<GroundedSet.Value> values, Set<String> marked) {
        List<GroundedSet.Value> withRef = values.stream().filter(v -> v.ref() != null).toList();
        List<GroundedSet.Value> markedValues = withRef.stream().filter(v -> marked.contains(v.ref())).toList();
        if (!markedValues.isEmpty()) {
            return markedValues;
        }
        long refs = withRef.stream().map(GroundedSet.Value::ref).distinct().count();
        boolean exact = figure.form() == Figure.Form.EXACT && figure.kind() != Figure.Kind.COUNT;
        return refs == 1 || exact ? withRef : List.of();
    }

    private static List<Integer> sentenceEnds(String text) {
        List<Integer> ends = new ArrayList<>();
        Matcher m = SENTENCE_END.matcher(text);
        while (m.find()) {
            ends.add(m.end());
        }
        return ends;
    }

    private static int sentence(List<Integer> ends, int position) {
        int i = 0;
        while (i < ends.size() && ends.get(i) <= position) {
            i++;
        }
        return i;
    }
}
