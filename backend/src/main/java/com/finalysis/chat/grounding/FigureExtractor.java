package com.finalysis.chat.grounding;

import com.finalysis.chat.grounding.Figure.Form;
import com.finalysis.chat.grounding.Figure.Kind;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds every figure in an answer (docs/plans/phase-6.md §5.2), pure Java. It fails closed: every run of digits is a
 * figure unless it is masked first as one of the known non-figures:
 * <ul>
 *   <li>citation markers ({@code [t3]}, {@code [c1, a2]});</li>
 *   <li>ISO dates and months ({@code 2026-08-22}, {@code 2026-08}), and dates with slashes ({@code 8/22/2026});</li>
 *   <li>a day or a year 2000–2099 next to a month name ({@code Aug 22}, {@code 22 August}, {@code August 2026},
 *       {@code Aug 22, 2026});</li>
 *   <li>ordinals ({@code 1st}, {@code 3rd}) and the numbers inside alias tokens ({@code CARD-1});</li>
 *   <li>quarter references and their year: {@code Q3}, {@code Q3 2026}, {@code 2026 Q3}, {@code Q3'26},
 *       {@code quarter 3}, {@code second quarter of 2026};</li>
 *   <li>labels this turn sent that contain only whole numbers (a merchant {@code 7-ELEVEN}, a category name), given
 *       by the caller; a label with a decimal, a {@code $} or a {@code %} is never masked;</li>
 *   <li>the whole numbers 0 and 1 as counts.</li>
 * </ul>
 * Backticked code is <em>not</em> skipped (a deliberate departure from §5.2's wording): an answer is plain text, and a
 * figure in backticks would otherwise be shown unchecked. Words ("twelve hundred") are not extracted (§15).
 */
public final class FigureExtractor {

    static final String MONTH_NAME = "(?:January|February|March|April|May|June|July|August|September|October|"
            + "November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept|Sep|Oct|Nov|Dec)\\.?";

    private static final Pattern MARKER = Pattern.compile("\\[\\s*[tcas]\\d+(?:\\s*,\\s*[tcas]\\d+)*\\s*]");
    private static final Pattern ISO_DATE = Pattern.compile("(?<!\\d)\\d{4}-\\d{2}(?:-\\d{2})?(?!\\d)");
    private static final Pattern SLASH_DATE = Pattern.compile("(?<!\\d)\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?(?!\\d)");
    private static final Pattern MONTH_DAY = Pattern.compile("(?i)\\b" + MONTH_NAME
            + "\\s+(?<day>\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(?<year>20\\d{2}))?(?!\\d)");
    private static final Pattern DAY_MONTH = Pattern.compile("(?i)(?<!\\d)(?<day>\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?"
            + MONTH_NAME + "(?:,?\\s+(?<year>20\\d{2}))?(?!\\d)");
    private static final Pattern MONTH_YEAR = Pattern.compile("(?i)\\b" + MONTH_NAME
            + ",?\\s+(?:of\\s+)?(?<year>20\\d{2})(?!\\d)");
    private static final Pattern YEAR_MONTH = Pattern.compile("(?i)(?<!\\d)(?<year>20\\d{2})\\s+" + MONTH_NAME + "\\b");
    private static final Pattern ORDINAL = Pattern.compile("(?i)(?<!\\d)\\d+(?:st|nd|rd|th)\\b");
    private static final Pattern QUARTER = Pattern.compile("(?i)(?<![\\p{L}\\d])(?:(?:20\\d{2}\\s*[-/]?\\s*)?Q[1-4]"
            + "(?:\\s*(?:,|of)?\\s*(?:20\\d{2}|'\\d{2})(?![.,]\\d))?|quarter\\s+[1-4]|(?:first|second|third|fourth|last|1st|2nd|3rd|4th)"
            + "\\s+quarter(?:\\s*,?\\s*(?:of\\s+)?20\\d{2}(?![.,]\\d))?)(?![\\p{L}\\d])");
    private static final Pattern ALIAS = Pattern.compile("\\b[A-Z][A-Z_]*-\\d+\\b");

    private static final Pattern NUMBER = Pattern.compile(
            "(?<open>\\()?(?<sign>[-−])?(?<cur>\\$)?(?<sign2>[-−])?"
                    + "(?<num>\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)"
                    + "(?<close>\\))?"
                    + "(?<pct>\\s?%|\\s+percent\\b|\\s+per\\s+cent\\b)?"
                    + "(?<scale>\\s?(?:k|K|M|bn|B)\\b|\\s+(?:thousand|million|billion|grand)\\b)?");
    private static final Pattern SAFE_LABEL = Pattern.compile("[^$%]*");
    private static final Pattern DECIMAL = Pattern.compile("\\d[.,]\\d");

    private FigureExtractor() {
    }

    /** The figures of an answer, in order. */
    public static List<Figure> extract(String answer) {
        return extract(answer, List.of());
    }

    /**
     * @param labels texts this turn sent as labels (merchants, category names, aliases); their whole numbers are not
     *               figures where the label appears whole
     */
    public static List<Figure> extract(String answer, Collection<String> labels) {
        char[] masked = answer.toCharArray();
        mask(masked, MARKER, null);
        mask(masked, ISO_DATE, null);
        mask(masked, SLASH_DATE, null);
        maskDays(masked, MONTH_DAY);
        maskDays(masked, DAY_MONTH);
        mask(masked, MONTH_YEAR, "year");
        mask(masked, YEAR_MONTH, "year");
        mask(masked, QUARTER, null);
        mask(masked, ORDINAL, null);
        mask(masked, ALIAS, null);
        for (String label : labels) {
            maskLabel(masked, label);
        }

        List<Figure> figures = new ArrayList<>();
        Matcher m = NUMBER.matcher(new String(masked));
        while (m.find()) {
            Figure figure = figure(m, answer);
            if (figure != null) {
                figures.add(figure);
            }
        }
        return List.copyOf(figures);
    }

    private static Figure figure(Matcher m, String answer) {
        String num = m.group("num");
        String plain = num.replace(",", "");
        int point = plain.indexOf('.');
        int decimals = point < 0 ? 0 : plain.length() - point - 1;
        BigDecimal magnitude = new BigDecimal(plain);
        boolean scaled = m.group("scale") != null;
        Kind kind;
        Form form;
        if (m.group("pct") != null) {
            kind = Kind.PERCENT;
            form = decimals == 0 ? Form.WHOLE : decimals == 1 ? Form.EXACT : Form.UNMATCHABLE;
        } else if (m.group("cur") != null || decimals == 2) {
            kind = Kind.MONEY;
            form = decimals == 2 ? Form.EXACT : decimals == 0 ? Form.WHOLE : Form.UNMATCHABLE;
        } else {
            kind = Kind.COUNT;
            form = decimals == 0 ? Form.EXACT : Form.UNMATCHABLE;
            if (form == Form.EXACT && !scaled && magnitude.signum() >= 0 && magnitude.compareTo(BigDecimal.ONE) <= 0) {
                return null; // the whole numbers 0 and 1 are not figures (§5.2)
            }
        }
        if (scaled) {
            form = Form.UNMATCHABLE; // $1.2k, 1.18 thousand: rounding the model may not do (§5.4)
        }
        int start = m.start();
        int end = m.end();
        if (m.group("open") != null && m.group("close") == null) {
            start = m.start("open") + 1; // an opening parenthesis that isn't a negative amount's
        }
        if (m.group("close") != null && m.group("open") == null) {
            end = m.end("num"); // a closing parenthesis that isn't a negative amount's
        }
        return new Figure(kind, form, magnitude, answer.substring(start, end).strip(), start, end);
    }

    private static void mask(char[] text, Pattern pattern, String group) {
        Matcher m = pattern.matcher(new String(text));
        while (m.find()) {
            if (group == null) {
                blank(text, m.start(), m.end());
            } else if (m.group(group) != null) {
                blank(text, m.start(group), m.end(group));
            }
        }
    }

    /** A day next to a month name, only when it can be a day; its year too. */
    private static void maskDays(char[] text, Pattern pattern) {
        Matcher m = pattern.matcher(new String(text));
        while (m.find()) {
            int day = Integer.parseInt(m.group("day"));
            if (day >= 1 && day <= 31) {
                blank(text, m.start("day"), m.end("day"));
                if (m.group("year") != null) {
                    blank(text, m.start("year"), m.end("year"));
                }
            }
        }
    }

    /** A label's digits where the label appears whole; never a label that could carry an amount or a percent. */
    private static void maskLabel(char[] text, String label) {
        if (label == null || label.isBlank() || label.chars().noneMatch(Character::isDigit)
                || !SAFE_LABEL.matcher(label).matches() || DECIMAL.matcher(label).find()) {
            return;
        }
        Matcher m = Pattern.compile("(?<![\\p{L}\\d])" + Pattern.quote(label) + "(?![\\p{L}\\d])")
                .matcher(new String(text));
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                if (Character.isDigit(text[i])) {
                    text[i] = ' ';
                }
            }
        }
    }

    private static void blank(char[] text, int start, int end) {
        for (int i = start; i < end; i++) {
            text[i] = ' ';
        }
    }
}
