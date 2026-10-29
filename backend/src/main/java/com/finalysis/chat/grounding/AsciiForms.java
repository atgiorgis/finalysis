package com.finalysis.chat.grounding;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A copy of an answer with its Unicode spaces, dashes and brackets in their ASCII form, one character for one, so a
 * position in the copy is the same position in the answer (Phase 9 steps 9a-2 and 9a-3). Grounding reads the copy to
 * find figures ({@link FigureExtractor}), citation markers ({@link CitationMarkers}) and sentence ends
 * ({@link AnswerGrounding}); what is shown, and a figure's text as written, come from the answer itself.
 *
 * <ul>
 *   <li>a no-break, figure, thin or narrow no-break space between digit groups ({@code 3 638.53} with U+202F, 1–3
 *       digits then groups of exactly 3) becomes a comma, a thousands mark;</li>
 *   <li>any other Unicode space ({@link Character#isSpaceChar}), and the zero-width characters U+200B, U+200C, U+200D,
 *       U+2060 and U+FEFF, become a space; U+2028 and U+2029 become a line break;</li>
 *   <li>U+2010, U+2011, U+2012, U+2013, U+FE63 and U+FF0D become {@code -}; the minus sign U+2212 is already a sign
 *       and the em dash is punctuation, so both stay;</li>
 *   <li>lenticular and full-width brackets ({@code 【c1】}, U+3010 and U+3011; U+FF3B and U+FF3D) become {@code [ ]},
 *       and the full-width comma U+FF0C a comma, so a marker in those forms is a marker.</li>
 * </ul>
 * Characters below U+0080 are never changed: ASCII text reads exactly as it did before these steps. In particular an
 * ASCII space is never a thousands mark.
 */
final class AsciiForms {

    /** Digit groups split by a Unicode space (a no-break, figure, thin or narrow no-break space): a thousands mark. */
    private static final Pattern UNICODE_GROUPED = Pattern.compile(
            "(?<![\\d.,])\\d{1,3}(?:[\\u00A0\\u2007\\u2009\\u202F]\\d{3})+(?!\\d)");

    private AsciiForms() {
    }

    static char[] of(String text) {
        char[] out = text.toCharArray();
        Matcher grouped = UNICODE_GROUPED.matcher(text);
        while (grouped.find()) {
            for (int i = grouped.start(); i < grouped.end(); i++) {
                if (!Character.isDigit(out[i])) {
                    out[i] = ',';
                }
            }
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = asciiForm(out[i]);
        }
        return out;
    }

    static String string(String text) {
        return new String(of(text));
    }

    private static char asciiForm(char c) {
        if (c < 0x80) {
            return c;
        }
        return switch (c) {
            case '‐', '‑', '‒', '–', '﹣', '－' -> '-';
            case ' ', ' ' -> '\n';
            case '​', '‌', '‍', '⁠', '﻿' -> ' ';
            case '【', '［' -> '[';
            case '】', '］' -> ']';
            case '，' -> ',';
            default -> Character.isSpaceChar(c) ? ' ' : c;
        };
    }
}
