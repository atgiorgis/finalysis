package com.finalysis.ingestion.pdf.boa;

import com.finalysis.ingestion.SourceSection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Every piece of text the Bank of America checking layout is recognized and read by: headings,
 * summary labels and the patterns for dates, amounts and page furniture (docs/plans/phase-3b.md
 * §3). Nothing outside this package knows any of it.
 *
 * <p>No pattern here names the institution: the layout is judged by its structure only (§1.5).
 * Points marked <b>guess</b> come from a structure-only review and are confirmed with the masked
 * skeleton of a real statement (step 5) before the parser locks them into fingerprints.
 */
final class BankOfAmericaLayout {

    /** {@code April 1, 2026}. */
    static final String LONG_DATE = "(?:January|February|March|April|May|June|July|August|September|October"
            + "|November|December) \\d{1,2}, \\d{4}";

    /**
     * A printed amount: an optional minus, an optional dollar sign, thousands separators and two
     * decimals ({@code -1,850.00}, {@code $3,200.00}, {@code -$4,033.14}, {@code -0.00}). Guess: no
     * parentheses, trailing minus or CR/DR suffixes.
     */
    static final String AMOUNT = "-?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}";
    static final Pattern AMOUNT_WORD = Pattern.compile(AMOUNT);

    /** A row's date column: {@code MM/DD/YY} (guess), or {@code MM/DD} should the skeleton show no year. */
    static final Pattern ROW_DATE_WORD = Pattern.compile("(?:0[1-9]|1[0-2])/(?:0[1-9]|[12]\\d|3[01])(?:/\\d{2})?");

    /** A check-number cell: digits, then the gap-in-sequence marker {@code *} if printed. */
    static final Pattern CHECK_NUMBER_WORD = Pattern.compile("\\d+\\*?");

    static final Pattern PERIOD = Pattern.compile("for (" + LONG_DATE + ") to (" + LONG_DATE + ")");
    static final Pattern ACCOUNT = Pattern.compile("Account number: (\\d+(?: \\d+)*)");
    static final Pattern BEGINNING_BALANCE = Pattern.compile("Beginning balance on (" + LONG_DATE + ") (" + AMOUNT + ")");
    static final Pattern ENDING_BALANCE = Pattern.compile("Ending balance on (" + LONG_DATE + ") (" + AMOUNT + ")");

    /**
     * The section headings, in printed order, and their sections (§3.2, fingerprint-relevant). Each
     * heading is also its account-summary label.
     */
    static final Map<String, SourceSection> SECTION_HEADINGS = sectionHeadings();

    /** Guess at the exact suffix of a section heading repeated on a later page. */
    static final String CONTINUED_SUFFIX = " - continued";

    /** A section's total line is this prefix, the heading in lower case, then the amount. */
    static final String TOTAL_PREFIX = "Total ";

    /** The column header of a transaction section, word by word. */
    static final List<String> COLUMN_HEADER = List.of("Date", "Description", "Amount");

    /** One column group of the checks section's header; it repeats once per group side by side. */
    static final List<String> CHECK_COLUMN_GROUP = List.of("Date", "Check", "#", "Amount");

    static final Pattern PAGE_NUMBER = Pattern.compile("Page \\d+ of \\d+");
    static final String CONTINUED_ON_NEXT_PAGE = "continued on the next page";

    private BankOfAmericaLayout() {
    }

    private static Map<String, SourceSection> sectionHeadings() {
        Map<String, SourceSection> headings = new LinkedHashMap<>();
        headings.put("Deposits and other additions", SourceSection.DEPOSIT);
        headings.put("Withdrawals and other subtractions", SourceSection.WITHDRAWAL);
        headings.put("Checks", SourceSection.CHECK);
        headings.put("Service fees", SourceSection.FEE);
        return Collections.unmodifiableMap(headings);
    }

    /** The heading's section when {@code text} is exactly a known heading, else null. */
    static SourceSection heading(String text) {
        return SECTION_HEADINGS.get(text);
    }

    /** The section when {@code text} is a known heading followed by {@link #CONTINUED_SUFFIX}, else null. */
    static SourceSection continuedHeading(String text) {
        return text.endsWith(CONTINUED_SUFFIX)
                ? heading(text.substring(0, text.length() - CONTINUED_SUFFIX.length()))
                : null;
    }

    /** The section when {@code text} is its total line ({@code Total <heading in lower case> <amount>}), else null. */
    static SourceSection total(String text) {
        for (Map.Entry<String, SourceSection> heading : SECTION_HEADINGS.entrySet()) {
            String label = TOTAL_PREFIX + heading.getKey().toLowerCase(Locale.ROOT) + " ";
            if (text.startsWith(label) && AMOUNT_WORD.matcher(text.substring(label.length())).matches()) {
                return heading.getValue();
            }
        }
        return null;
    }

    /** The section when {@code text} is its account-summary line ({@code <heading> <amount>}), else null. */
    static SourceSection summary(String text) {
        for (Map.Entry<String, SourceSection> heading : SECTION_HEADINGS.entrySet()) {
            String label = heading.getKey() + " ";
            if (text.startsWith(label) && AMOUNT_WORD.matcher(text.substring(label.length())).matches()) {
                return heading.getValue();
            }
        }
        return null;
    }

    /** {@code Page N of M} or the continued-on-next-page note: page furniture wherever it appears. */
    static boolean isPageFooter(String text) {
        return PAGE_NUMBER.matcher(text).matches() || text.equals(CONTINUED_ON_NEXT_PAGE);
    }
}
