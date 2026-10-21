package com.finalysis.ai.outbound;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one text normalization shared by cleaning, rules and redaction (docs/plans/phase-6.md §18, "The move"): line
 * breaks and tabs to spaces, accents removed, curly apostrophes straightened, control and format characters dropped,
 * upper case, single spaces, at most {@value #MAX_INPUT} characters. {@code MerchantCleaner.normalize} delegates here.
 */
public final class TextNormalizer {

    public static final int MAX_INPUT = 512;

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern LINE_BREAKS_AND_TABS = Pattern.compile("[\\r\\n\\t\\u000B\\f\\u0085\\u2028\\u2029]");
    private static final Pattern CONTROL_AND_FORMAT = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private TextNormalizer() {
    }

    public static String normalize(String raw) {
        String text = LINE_BREAKS_AND_TABS.matcher(raw).replaceAll(" ");
        text = Normalizer.normalize(text, Normalizer.Form.NFKD);
        text = MARKS.matcher(text).replaceAll("");
        text = text.replace('‘', '\'').replace('’', '\'');
        text = CONTROL_AND_FORMAT.matcher(text).replaceAll("");
        text = SPACES.matcher(text.toUpperCase(Locale.ROOT)).replaceAll(" ").strip();
        return text.length() > MAX_INPUT ? text.substring(0, MAX_INPUT) : text;
    }
}
