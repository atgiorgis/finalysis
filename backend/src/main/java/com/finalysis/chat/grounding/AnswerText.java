package com.finalysis.chat.grounding;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a model's answer plain text before anything else reads it (docs/plans/phase-6.md §12.5, point 5): images,
 * HTML tags, URLs and link targets are removed, a markdown link keeps only its text. A Phase 7 renderer can then never
 * be made to fetch an attacker's URL, and grounding checks exactly the text that will be shown.
 */
public final class AnswerText {

    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern REFERENCE_DEFINITION = Pattern.compile("(?m)^[ \\t]*\\[[^\\]]+]:[ \\t]*\\S+.*$\\R?");
    private static final Pattern AUTOLINK = Pattern.compile("(?i)<(?:https?|ftp|mailto|data|javascript):[^>]*>");
    private static final Pattern TAG = Pattern.compile("</?[A-Za-z][^>]*>");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|ftp)://\\S+|\\bwww\\.\\S+"
            + "|\\b(?:data|javascript):\\S+");
    private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");

    /** The plain text, and how many things were removed (counted, never kept). */
    public record Plain(String text, int removed) {
    }

    private AnswerText() {
    }

    public static Plain plain(String answer) {
        int[] removed = {0};
        String text = answer;
        text = replace(IMAGE, text, m -> "", removed);
        text = replace(LINK, text, m -> m.group(1), removed);
        text = replace(REFERENCE_DEFINITION, text, m -> "", removed);
        text = replace(AUTOLINK, text, m -> "", removed);
        text = replace(TAG, text, m -> "", removed);
        text = replace(URL, text, m -> "", removed);
        text = SPACES.matcher(text).replaceAll(" ").strip();
        return new Plain(text, removed[0]);
    }

    private static String replace(Pattern pattern, String text, Function<Matcher, String> with,
                                  int[] removed) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            removed[0]++;
            m.appendReplacement(out, Matcher.quoteReplacement(with.apply(m)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
