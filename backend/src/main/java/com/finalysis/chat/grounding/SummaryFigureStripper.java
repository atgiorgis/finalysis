package com.finalysis.chat.grounding;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Takes every figure out of a conversation's memory (CHT-06, docs/plans/phase-6.md §7.1, §7.2): the summary the model
 * writes, and the earlier answers sent back as history. Grounding is per turn, so a figure from an earlier turn could
 * never ground a new answer anyway; stripping it means the model can't even copy one, and every figure in a new answer
 * needs a new tool call. Pure Java, so it holds even when the summary prompt is ignored.
 * <ul>
 *   <li>every figure {@link FigureExtractor} finds (it fails closed: any number but a date, a month, a day or year
 *       beside a month name, an ordinal, an alias's digits, 0 or 1) becomes {@code [amount]}, {@code [percent]} or
 *       {@code [number]};</li>
 *   <li>citation markers ({@code [1]}, {@code [c2, t3]}) are removed: refs are per turn and mean nothing later;</li>
 *   <li>{@code < > { }} are removed, so the text passes the guard's history rule; runs of spaces collapse.</li>
 * </ul>
 * The result is checked again: if the extractor still finds a figure, the text is refused (empty), never sent.
 */
public final class SummaryFigureStripper {

    static final String AMOUNT = "[amount]";
    static final String PERCENT = "[percent]";
    static final String NUMBER = "[number]";

    private static final Pattern SHOWN_MARKER = Pattern.compile("\\s?\\[\\s*[tcas]?\\d+(?:\\s*,\\s*[tcas]?\\d+)*\\s*]");
    private static final Pattern FORBIDDEN = Pattern.compile("[<>{}]");
    private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");

    private SummaryFigureStripper() {
    }

    /**
     * The text with every figure replaced and every marker removed; empty when nothing is left or a figure survived.
     */
    public static String strip(String text) {
        if (text == null) {
            return "";
        }
        String plain = SHOWN_MARKER.matcher(text).replaceAll("");
        plain = FORBIDDEN.matcher(plain).replaceAll(" ");
        List<Figure> figures = FigureExtractor.extract(plain);
        StringBuilder out = new StringBuilder(plain.length());
        int at = 0;
        for (Figure figure : figures) {
            out.append(plain, at, figure.start()).append(switch (figure.kind()) {
                case MONEY -> AMOUNT;
                case PERCENT -> PERCENT;
                case COUNT -> NUMBER;
            });
            at = figure.end();
        }
        out.append(plain, at, plain.length());
        String stripped = SPACES.matcher(out).replaceAll(" ").strip();
        return figureFree(stripped) ? stripped : "";
    }

    /** Whether a text has no figure left, by the same extractor grounding uses. */
    public static boolean figureFree(String text) {
        return FigureExtractor.extract(text).isEmpty();
    }
}
