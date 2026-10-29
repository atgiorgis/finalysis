package com.finalysis.chat.grounding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The markers a model writes ({@code [t3]}, {@code [c1, a2]}) (docs/plans/phase-6.md §6). A marker must be a ref this
 * turn issued: anything else in marker form ({@code [t99]}, {@code [7]}) is removed from the text and counted, never
 * resolved. Kept markers are later renumbered {@code [1]}, {@code [2]}… in order of first appearance.
 *
 * <p>Markers are found on the text's {@link AsciiForms} copy (Phase 9 step 9a-3), so {@code 【c1】} (lenticular
 * brackets), {@code ［c1］} (full-width) and {@code [c1]} with a zero-width space inside are the marker {@code c1}, like
 * {@code [c1]}. A marker group is written back in its ASCII form; every other character is kept as written.
 */
public final class CitationMarkers {

    /** Anything in marker form: brackets around refs or numbers, comma-separated. */
    private static final Pattern GROUP = Pattern.compile(
            "[ \\t]?\\[\\s*([A-Za-z]?\\d+(?:\\s*,\\s*[A-Za-z]?\\d+)*)\\s*]");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z]?\\d+");

    /** One kept ref and the span of its marker group in the parsed text. */
    public record Marker(String ref, int start, int end) {
    }

    /**
     * The text with only issued refs in its markers, those markers in order, and how many marker refs were dropped.
     */
    public record Parsed(String text, List<Marker> markers, int dropped) {

        public Parsed {
            markers = List.copyOf(markers);
        }

        /** The distinct refs, in order of first appearance. */
        public List<String> refs() {
            return markers.stream().map(Marker::ref).distinct().toList();
        }
    }

    private CitationMarkers() {
    }

    public static Parsed parse(String text, Predicate<String> issued) {
        Matcher m = GROUP.matcher(AsciiForms.string(text));
        StringBuilder out = new StringBuilder();
        List<Marker> markers = new ArrayList<>();
        int dropped = 0;
        int last = 0;
        while (m.find()) {
            List<String> kept = new ArrayList<>();
            Matcher token = TOKEN.matcher(m.group(1));
            while (token.find()) {
                if (issued.test(token.group())) {
                    kept.add(token.group());
                } else {
                    dropped++;
                }
            }
            out.append(text, last, m.start());
            last = m.end();
            if (!kept.isEmpty()) {
                out.append(lead(text, m));
                int start = out.length();
                out.append('[').append(String.join(", ", kept)).append(']');
                for (String ref : kept) {
                    markers.add(new Marker(ref, start, out.length()));
                }
            }
        }
        out.append(text, last, text.length());
        return new Parsed(out.toString(), markers, dropped);
    }

    /** The text with every kept marker group renumbered; refs without a number are left out of their group. */
    public static String renumber(String text, Map<String, Integer> numbers) {
        Matcher m = GROUP.matcher(AsciiForms.string(text));
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            List<Integer> kept = new ArrayList<>();
            Matcher token = TOKEN.matcher(m.group(1));
            while (token.find()) {
                Integer number = numbers.get(token.group());
                if (number != null && !kept.contains(number)) {
                    kept.add(number);
                }
            }
            out.append(text, last, m.start());
            last = m.end();
            if (!kept.isEmpty()) {
                out.append(lead(text, m)).append(kept.stream().map(String::valueOf)
                        .collect(Collectors.joining(", ", "[", "]")));
            }
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /** The space or tab before a marker group, as written; empty when the group starts at its bracket. */
    private static String lead(String text, Matcher m) {
        return m.group().startsWith("[") ? "" : text.substring(m.start(), m.start() + 1);
    }
}
