package com.finalysis.ingestion.pdf.boa;

import com.finalysis.ingestion.SourceSection;
import com.finalysis.ingestion.pdf.PdfDocumentText;
import com.finalysis.ingestion.pdf.PdfLine;
import com.finalysis.ingestion.pdf.PdfWord;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Gives every extracted line of a Bank of America checking statement exactly one {@link LineKind}
 * (docs/plans/phase-3b.md §3.1), and finds the detection markers (§1.5). The parser and the
 * skeleton debug tool share it, so the tool shows exactly what the parser sees.
 *
 * <p>Classification is by shape and never fails: deciding whether the lines make a valid statement
 * (a row outside a section, a total for a section that isn't open, an unknown heading) is the
 * parser's section state machine. So a row shaped like a transaction is a {@link LineKind#TXN} even
 * under an unknown heading, and the parser can name its line.
 *
 * <p>Three passes:
 * <ol>
 *   <li>Shapes, line by line: page furniture, the period, account and summary lines, headings,
 *       totals, column headers, and rows aligned to the most recent column header on the page.</li>
 *   <li>Continuations (§3.3), which need each section's median row pitch from pass 1.</li>
 *   <li>Page headers and footers: lines left unclassified above a later page's first structural
 *       line, or below any page's last.</li>
 * </ol>
 */
public final class BankOfAmericaLineClassifier {

    /** How far a word may sit from its column's edge, in points. */
    static final float COLUMN_TOLERANCE = 3f;

    /** A continuation may be at most this many median row pitches below the line above it (§3.3 rule 4). */
    static final float MAX_CONTINUATION_PITCHES = 1.6f;

    /** The kinds of line in this layout (§3.1). */
    public enum LineKind {
        PERIOD,
        ACCOUNT,
        SUMMARY,
        SECTION_START,
        SECTION_CONTINUED,
        COLUMN_HEADER,
        TXN,
        CHECK_ROW,
        CONTINUATION,
        TOTAL,
        PAGE_HEADER,
        PAGE_FOOTER,
        OTHER;

        /** Lines that are part of the statement's structure; page headers and footers are bounded by them. */
        boolean structural() {
            return this != PAGE_HEADER && this != PAGE_FOOTER && this != OTHER;
        }
    }

    /**
     * A line and its kind.
     *
     * @param section the section a heading, total or summary line names, or that a column header,
     *                row or continuation sits in; null when there is none (the balance summary
     *                lines, a row outside any section, every other kind)
     */
    public record ClassifiedLine(PdfLine line, LineKind kind, SourceSection section) {
    }

    /**
     * The detection markers on page 1 (§1.5), each present only in its place: the summary section
     * lines below the beginning balance, the ending balance below them.
     */
    public record Markers(boolean period, boolean beginningBalance, boolean deposits, boolean withdrawals,
                          boolean endingBalance) {

        public boolean all() {
            return period && beginningBalance && deposits && withdrawals && endingBalance;
        }
    }

    /** One column group: a date column, a middle column (description or check number), an amount column. */
    private record Group(float dateX, float middleX, float amountXEnd) {
    }

    /** The columns a column header sets; {@code checks} for the checks section's side-by-side groups. */
    private record Columns(List<Group> groups, boolean checks) {
    }

    private BankOfAmericaLineClassifier() {
    }

    public static Markers markers(PdfDocumentText text) {
        if (text.pages().isEmpty()) {
            return new Markers(false, false, false, false, false);
        }
        List<PdfLine> page = text.pages().getFirst().lines();
        boolean period = page.stream().anyMatch(line -> BankOfAmericaLayout.PERIOD.matcher(line.text()).matches());
        SummaryBlock block = SummaryBlock.of(page);
        return new Markers(period, block.beginning >= 0, block.deposits >= 0, block.withdrawals >= 0,
                block.ending >= 0);
    }

    /** Every line of the document, in line-number order, with its kind. */
    public static List<ClassifiedLine> classify(PdfDocumentText text) {
        List<PdfLine> lines = text.lines();
        int n = lines.size();
        LineKind[] kinds = new LineKind[n];
        SourceSection[] sections = new SourceSection[n];
        // For pass 2: the columns and open section in force at each line pass 1 left as OTHER.
        Columns[] columnsAt = new Columns[n];
        SourceSection[] openAt = new SourceSection[n];

        SummaryBlock block = text.pages().isEmpty() ? SummaryBlock.NONE : SummaryBlock.of(text.pages().getFirst().lines());
        boolean periodSeen = false;
        Columns columns = null;
        SourceSection open = null;
        int page = 0;
        for (int i = 0; i < n; i++) {
            PdfLine line = lines.get(i);
            String t = line.text();
            if (line.page() != page) {
                page = line.page();
                columns = null;
            }
            SourceSection section;
            Columns header;
            LineKind kind;
            if (BankOfAmericaLayout.isPageFooter(t)) {
                kind = LineKind.PAGE_FOOTER;
                section = null;
            } else if (page == 1 && !periodSeen && BankOfAmericaLayout.PERIOD.matcher(t).matches()) {
                kind = LineKind.PERIOD;
                section = null;
                periodSeen = true;
            } else if (BankOfAmericaLayout.ACCOUNT.matcher(t).matches()) {
                kind = LineKind.ACCOUNT;
                section = null;
            } else if (page == 1 && block.contains(i) && block.isSummaryLine(t)) {
                kind = LineKind.SUMMARY;
                section = BankOfAmericaLayout.summary(t);
            } else if ((section = BankOfAmericaLayout.heading(t)) != null) {
                kind = LineKind.SECTION_START;
                open = section;
                columns = null;
            } else if ((section = BankOfAmericaLayout.continuedHeading(t)) != null) {
                kind = LineKind.SECTION_CONTINUED;
                open = section;
                columns = null;
            } else if ((section = BankOfAmericaLayout.total(t)) != null) {
                kind = LineKind.TOTAL;
                open = null;
                columns = null;
            } else if ((header = columnHeader(line)) != null) {
                kind = LineKind.COLUMN_HEADER;
                section = open;
                columns = header;
            } else if (columns != null && columns.checks() && isCheckRow(line, columns)) {
                kind = LineKind.CHECK_ROW;
                section = open;
            } else if (columns != null && !columns.checks() && isTxn(line, columns.groups().getFirst())) {
                kind = LineKind.TXN;
                section = open;
            } else {
                kind = LineKind.OTHER;
                section = null;
                columnsAt[i] = columns;
                openAt[i] = open;
            }
            kinds[i] = kind;
            sections[i] = section;
        }

        markContinuations(lines, kinds, sections, columnsAt, openAt);
        markPageHeadersAndFooters(lines, kinds);

        List<ClassifiedLine> classified = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            classified.add(new ClassifiedLine(lines.get(i), kinds[i], sections[i]));
        }
        return List.copyOf(classified);
    }

    /**
     * §3.3: a line is a continuation only if it sits in an open section directly below a row or
     * continuation on the same page, has no date in the date column and no amount in the amount
     * column, starts at the description column, and is at most 1.6 median row pitches below.
     */
    private static void markContinuations(List<PdfLine> lines, LineKind[] kinds, SourceSection[] sections,
                                          Columns[] columnsAt, SourceSection[] openAt) {
        Map<SourceSection, Float> pitch = medianRowPitch(lines, kinds, sections);
        for (int i = 1; i < lines.size(); i++) {
            Columns columns = columnsAt[i];
            SourceSection open = openAt[i];
            if (kinds[i] != LineKind.OTHER || open == null || columns == null || columns.checks()
                    || (kinds[i - 1] != LineKind.TXN && kinds[i - 1] != LineKind.CONTINUATION)) {
                continue;
            }
            PdfLine line = lines.get(i);
            PdfLine above = lines.get(i - 1);
            Group group = columns.groups().getFirst();
            Float rowPitch = pitch.get(open);
            if (line.page() == above.page()
                    && line.words().stream().noneMatch(word -> isDateAt(word, group) || isAmountAt(word, group))
                    && near(line.words().getFirst().x(), group.middleX())
                    && rowPitch != null && line.y() - above.y() <= MAX_CONTINUATION_PITCHES * rowPitch) {
                kinds[i] = LineKind.CONTINUATION;
                sections[i] = open;
            }
        }
    }

    /**
     * Per section, the median distance from each row to the line directly above it on the same page
     * (the column header, the previous row, or the previous row's last continuation). Measured at
     * row starts, so it is the line pitch whatever the rows' wraps.
     */
    private static Map<SourceSection, Float> medianRowPitch(List<PdfLine> lines, LineKind[] kinds,
                                                           SourceSection[] sections) {
        Map<SourceSection, List<Float>> gaps = new EnumMap<>(SourceSection.class);
        for (int i = 1; i < lines.size(); i++) {
            if (kinds[i] == LineKind.TXN && sections[i] != null && lines.get(i).page() == lines.get(i - 1).page()) {
                gaps.computeIfAbsent(sections[i], s -> new ArrayList<>()).add(lines.get(i).y() - lines.get(i - 1).y());
            }
        }
        Map<SourceSection, Float> medians = new EnumMap<>(SourceSection.class);
        gaps.forEach((section, values) -> {
            List<Float> sorted = values.stream().sorted().toList();
            int middle = sorted.size() / 2;
            medians.put(section, sorted.size() % 2 == 1 ? sorted.get(middle)
                    : (sorted.get(middle - 1) + sorted.get(middle)) / 2);
        });
        return medians;
    }

    /**
     * Lines still unclassified above the first structural line of a page after the first are its
     * header; those below the last structural line of any page are its footer. A page without any
     * structural line keeps its lines as {@link LineKind#OTHER}.
     */
    private static void markPageHeadersAndFooters(List<PdfLine> lines, LineKind[] kinds) {
        int start = 0;
        while (start < lines.size()) {
            int page = lines.get(start).page();
            int end = start;
            while (end < lines.size() && lines.get(end).page() == page) {
                end++;
            }
            int first = -1;
            int last = -1;
            for (int i = start; i < end; i++) {
                if (kinds[i].structural()) {
                    first = first < 0 ? i : first;
                    last = i;
                }
            }
            if (first >= 0) {
                for (int i = start; i < end; i++) {
                    if (kinds[i] == LineKind.OTHER && page > 1 && i < first) {
                        kinds[i] = LineKind.PAGE_HEADER;
                    } else if (kinds[i] == LineKind.OTHER && i > last) {
                        kinds[i] = LineKind.PAGE_FOOTER;
                    }
                }
            }
            start = end;
        }
    }

    /** The columns of a column header line, or null when the line isn't one. */
    private static Columns columnHeader(PdfLine line) {
        List<PdfWord> words = line.words();
        if (texts(words).equals(BankOfAmericaLayout.COLUMN_HEADER)) {
            return new Columns(List.of(new Group(words.get(0).x(), words.get(1).x(), words.get(2).xEnd())), false);
        }
        int size = BankOfAmericaLayout.CHECK_COLUMN_GROUP.size();
        if (words.isEmpty() || words.size() % size != 0) {
            return null;
        }
        List<Group> groups = new ArrayList<>();
        for (int g = 0; g < words.size(); g += size) {
            List<PdfWord> group = words.subList(g, g + size);
            if (!texts(group).equals(BankOfAmericaLayout.CHECK_COLUMN_GROUP)) {
                return null;
            }
            groups.add(new Group(group.getFirst().x(), group.get(1).x(), group.getLast().xEnd()));
        }
        return new Columns(List.copyOf(groups), true);
    }

    /** First word a date in the date column, last word an amount right-aligned to the amount column. */
    private static boolean isTxn(PdfLine line, Group columns) {
        List<PdfWord> words = line.words();
        return words.size() >= 2 && isDateAt(words.getFirst(), columns) && isAmountAt(words.getLast(), columns);
    }

    /**
     * One or more {@code date checkno[*] amount} groups filling the header's groups from the left
     * (the last line may hold fewer), with no word left over.
     */
    private static boolean isCheckRow(PdfLine line, Columns columns) {
        List<PdfWord> words = line.words();
        int next = 0;
        for (Group group : columns.groups()) {
            if (next == words.size()) {
                break;
            }
            if (next + 3 > words.size()
                    || !isDateAt(words.get(next), group)
                    || !BankOfAmericaLayout.CHECK_NUMBER_WORD.matcher(words.get(next + 1).text()).matches()
                    || !near(words.get(next + 1).x(), group.middleX())
                    || !isAmountAt(words.get(next + 2), group)) {
                return false;
            }
            next += 3;
        }
        return next > 0 && next == words.size();
    }

    private static boolean isDateAt(PdfWord word, Group columns) {
        return near(word.x(), columns.dateX()) && BankOfAmericaLayout.ROW_DATE_WORD.matcher(word.text()).matches();
    }

    private static boolean isAmountAt(PdfWord word, Group columns) {
        return near(word.xEnd(), columns.amountXEnd()) && BankOfAmericaLayout.AMOUNT_WORD.matcher(word.text()).matches();
    }

    private static boolean near(float x, float column) {
        return Math.abs(x - column) <= COLUMN_TOLERANCE;
    }

    private static List<String> texts(List<PdfWord> words) {
        return words.stream().map(PdfWord::text).toList();
    }

    /**
     * Where the account summary sits on page 1, as indexes into the page's lines (-1 when absent):
     * the beginning balance, the deposits and withdrawals lines below it, and the ending balance
     * below those.
     */
    private record SummaryBlock(int beginning, int deposits, int withdrawals, int ending, int pageEnd) {

        static final SummaryBlock NONE = new SummaryBlock(-1, -1, -1, -1, 0);

        static SummaryBlock of(List<PdfLine> page) {
            int beginning = first(page, 0, line -> BankOfAmericaLayout.BEGINNING_BALANCE.matcher(line).matches());
            int after = beginning + 1;
            int deposits = first(page, after, line -> BankOfAmericaLayout.summary(line) == SourceSection.DEPOSIT);
            int withdrawals = first(page, after, line -> BankOfAmericaLayout.summary(line) == SourceSection.WITHDRAWAL);
            int ending = first(page, Math.max(after, Math.max(deposits, withdrawals) + 1),
                    line -> BankOfAmericaLayout.ENDING_BALANCE.matcher(line).matches());
            return new SummaryBlock(beginning, deposits, withdrawals, ending, page.size());
        }

        private static int first(List<PdfLine> page, int from, Predicate<String> matches) {
            for (int i = from; i < page.size(); i++) {
                if (matches.test(page.get(i).text())) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * Whether page 1's line at {@code index} lies from the beginning balance to the ending balance
         * (to the page's end if there is no ending balance). Page 1's lines come first in the document,
         * so the index is also the document index.
         */
        boolean contains(int index) {
            return beginning >= 0 && index >= beginning && index <= (ending >= 0 ? ending : pageEnd - 1);
        }

        boolean isSummaryLine(String text) {
            return BankOfAmericaLayout.BEGINNING_BALANCE.matcher(text).matches()
                    || BankOfAmericaLayout.ENDING_BALANCE.matcher(text).matches()
                    || BankOfAmericaLayout.summary(text) != null;
        }
    }
}
