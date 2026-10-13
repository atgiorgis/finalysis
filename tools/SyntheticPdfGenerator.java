// Generates synthetic PDF statements for the PDF parser tests (ING-11, docs/plans/phase-3b.md §5).
//
// Run from the repo root:  java tools/SyntheticPdfGenerator.java [output-dir]
// The output directory defaults to sample-data; files go under <output-dir>/pdf/. SyntheticPdfTest
// writes to a temporary directory and compares the result with the committed files byte for byte.
//
// Input is the committed answer key: the six Northfield checking statements are drawn from
// sample-data/answer-key.csv and sample-data/statements-manifest.csv and never re-derived here.
// Edge cases are hand-built Northfield statements; each gets a <name>.expected.csv next to it.
// Every fixture also gets a <name>.lines.txt: the text lines this generator drew, in the order and
// form PdfTextExtractor must read them back (top to bottom, words joined by one space). Every
// fixture in the checking layout also gets a <name>.labels.txt: the kind of each line as drawn
// (PERIOD, TXN, CONTINUATION, PAGE_FOOTER ...), which the layout's line classifier must reproduce.
//
// The PDF writer is dependency-free: standard-14 Helvetica and Helvetica-Bold, uncompressed content
// streams, no /Info, no /ID, fixed object order and locale-independent numbers, so the bytes are
// identical on every run. The institution (Northfield), customer, address and account are fictional,
// every page is marked SYNTHETIC TEST DATA, and no real bank name, logo or routing number appears.
// The layout reproduces the structure the BoA checking parser expects (§3), not any real statement.

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

public class SyntheticPdfGenerator {

    static final Path SAMPLE_DATA = Path.of("sample-data");
    static Path out = SAMPLE_DATA.resolve("pdf");

    static final String MARK = "SYNTHETIC TEST DATA";
    static final String CUSTOMER = "SAMPLE CUSTOMER";
    static final String ACCOUNT_NUMBER = "0047 3910 01";
    static final DateTimeFormatter ROW_DATE = DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);
    static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    // Geometry, in points from the page's top-left corner (US Letter).
    static final float PAGE_WIDTH = 612;
    static final float PAGE_HEIGHT = 792;
    static final float LEFT = 36;
    static final float DESCRIPTION_X = 90;
    static final float RIGHT = 576;
    static final float CHECK_NUMBER_X = 96;
    static final float CHECK_AMOUNT_RIGHT = 260;
    static final float CHECK_GROUP_2 = 320;
    static final float BODY = 9;
    static final float HEADING = 10;
    static final float SMALL = 8;
    static final float PITCH = 12;
    static final float BODY_BOTTOM = 712;
    static final float CONTINUED_Y = 726;
    static final float PAGE_NUMBER_Y = 742;
    static final float MARK_FOOTER_Y = 758;
    static final float MARK_HEADER_Y = 30;
    /** Narrow enough that the longer answer-key descriptions (payroll, rent, savings transfer) wrap at a word boundary. */
    static final float DESCRIPTION_WIDTH = 175;
    /** Smallest gap allowed between two strings on one line; the extractor ends a word at 0.3 x font size. */
    static final float MIN_COLUMN_GAP = 6;

    static final String HEADER_FILE = "record,line,section,printed_date,txn_date,printed_amount,amount,check_number,"
            + "raw_description,warning";

    public static void main(String[] args) throws IOException {
        if (!Files.isRegularFile(Path.of("tools", "SyntheticPdfGenerator.java"))) {
            System.err.println("Run from the repo root: java tools/SyntheticPdfGenerator.java");
            System.exit(1);
        }
        if (args.length > 0) {
            out = Path.of(args[0]).resolve("pdf");
        }
        new SyntheticPdfGenerator().run();
    }

    final List<String> manifest = new ArrayList<>(List.of("file,parser,unparsed_reason,answer_key_file,period_start,"
            + "period_end,opening_balance,closing_balance,status"));

    void run() throws IOException {
        clearOutput();
        List<Statement> monthly = monthlyStatements();
        for (Statement s : monthly) {
            writeStatement(s, false);
        }
        List<Statement> edgeCases = edgeCases(monthly.getFirst());
        for (Statement s : edgeCases) {
            writeStatement(s, true);
        }
        writeUnrecognizedLayout();
        writeNoText();
        write("pdf-manifest.csv", String.join("\n", manifest) + "\n");
        System.out.printf(Locale.ROOT, "Wrote %d monthly PDFs and %d edge cases to %s/%n", monthly.size(),
                edgeCases.size() + 2, out);
    }

    // ---------------------------------------------------------------- statements from the answer key

    List<Statement> monthlyStatements() throws IOException {
        Map<String, List<String[]>> rowsByFile = new LinkedHashMap<>();
        for (String[] c : readCsv(SAMPLE_DATA.resolve("answer-key.csv"))) {
            if (c[1].equals("northfield-checking")) {
                rowsByFile.computeIfAbsent(c[0], file -> new ArrayList<>()).add(c);
            }
        }
        List<Statement> statements = new ArrayList<>();
        for (String[] m : readCsv(SAMPLE_DATA.resolve("statements-manifest.csv"))) {
            if (!m[1].equals("northfield-checking") || !m[0].startsWith("statements/")) {
                continue;
            }
            String month = m[0].substring(m[0].indexOf('_') + 1, m[0].lastIndexOf('.'));
            Statement s = new Statement("northfield-checking_" + month + ".pdf", LocalDate.parse(m[2]),
                    LocalDate.parse(m[3]), money(m[4]));
            s.answerKeyFile = m[0];
            for (String[] c : rowsByFile.get(m[0])) {
                BigDecimal amount = money(c[4]);
                require(!c[3].matches(".*\\b\\d\\d/\\d\\d\\b.*"), "answer-key description looks like a date: " + c[3]);
                s.rows.add(new Row(LocalDate.parse(c[2]), amount.signum() > 0 ? Section.DEPOSIT : Section.WITHDRAWAL,
                        amount(amount), wrap(c[3]), null));
            }
            require(s.closing().compareTo(money(m[5])) == 0, m[0] + " does not reconcile with its manifest entry");
            // Odd months draw descriptions as TJ arrays with kerning; every second page draws amounts first.
            s.kerning = Integer.parseInt(month.substring(5)) % 2 == 1;
            s.amountsFirstPages = Set.of(2);
            statements.add(s);
        }
        require(statements.size() == 6, "expected six Northfield checking statements");
        return statements;
    }

    /** Splits a description at word boundaries so no line is wider than the description column. */
    static List<String> wrap(String description) {
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String word : description.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && width(candidate, false, BODY) > DESCRIPTION_WIDTH) {
                lines.add(line);
                line = word;
            } else {
                line = candidate;
            }
        }
        lines.add(line);
        return lines;
    }

    // ---------------------------------------------------------------- edge cases

    List<Statement> edgeCases(Statement april) {
        LocalDate start = LocalDate.of(2026, 4, 1);
        LocalDate end = LocalDate.of(2026, 4, 30);
        BigDecimal opening = money("1000.00");
        List<Statement> cases = new ArrayList<>();

        // Wraps of 2 to 5 lines, a mid-word break, digits and "/" (and a date-like token) in a
        // continuation, and a footer note just below the last row on page 1. Only the 5-line row
        // (4 continuation lines) exceeds §3.3's limit of 3 and warns.
        Statement wrapped = edgeCase("pdf-wrapped-descriptions", start, end, opening);
        wrapped.rows.addAll(List.of(
                deposit("04/03/26", "2,451.08", "BRIGHTWAY LOGISTICS PAYROLL PPD", "ID: 4419920113"),
                withdrawal("04/04/26", "-35.20", "AMAZON MKTPL", "ACE PMTS SEATTLE WA"),
                withdrawal("04/06/26", "-60.00", "ZELLE PAYMENT TO SAMPLE PAYEE", "CONF# 8G7H6J REF 2210/77 04/14"),
                withdrawal("04/08/26", "-98.40", "INSURANCE PREMIUM PAYMENT", "POLICY 44-1902-77",
                        "SAMPLE MUTUAL INSURANCE"),
                withdrawal("04/10/26", "-42.00", "PAYPAL INST XFER", "SAMPLE MARKETPLACE SELLER", "ORDER 5521-A",
                        "WEB ID: PAYPALSI77"),
                withdrawal("04/12/26", "-500.00", "WIRE TYPE:WIRE OUT DATE:260412", "TRN:2026041200001234",
                        "SERVICE REF:004417", "BNF:SAMPLE VENDOR LLC", "ID:0000000000000"),
                withdrawal("04/14/26", "-79.99", "SKYWAVE INTERNET AUTOPAY"),
                withdrawal("04/18/26", "-133.93", "METRO POWER CO ONLINE PMT"),
                withdrawal("04/25/26", "-46.81", "CITY OF ARLINGTON WATER UTIL PMT")));
        wrapped.breakAfterRow = 6;
        wrapped.breakNote = "Please see the last page for important information";
        wrapped.warn(5, Section.WITHDRAWAL, "DESCRIPTION_SPANS_LINES");
        cases.add(wrapped);

        // Withdrawals over three pages: page 2 is drawn bottom to top, page 3 amounts first.
        Statement multipage = edgeCase("pdf-multipage-continued", start, end, opening.add(money("2000.00")));
        multipage.rows.add(deposit("04/03/26", "2,451.08", "BRIGHTWAY LOGISTICS PAYROLL PPD", "ID: 4419920113"));
        String[] merchants = {"BLUE BEAN CAFE ARLINGTON VA", "CORNER MARKET ARLINGTON VA", "METRO TRANSIT FARE",
                "SAMPLE BOOKSHOP ONLINE", "GREENLEAF GROCERY #114", "FUEL STOP 2231 ARLINGTON VA",
                "NAKAMURA RAMEN ARLINGTON VA", "PINE STREET PHARMACY"};
        for (int i = 0; i < 26; i++) {
            BigDecimal amount = money("10.00").add(BigDecimal.valueOf(317L * i + 41, 2)).negate();
            multipage.rows.add(withdrawal(ROW_DATE.format(start.plusDays(1 + i)), amount(amount),
                    merchants[i % merchants.length]));
        }
        multipage.rowsPerPage = 10;
        multipage.reversedPages = Set.of(2);
        multipage.amountsFirstPages = Set.of(3);
        cases.add(multipage);

        // $0.00 rows: "0.00" in deposits, "-0.00" in withdrawals, and two identical zero rows.
        Statement zero = edgeCase("pdf-zero-rows", start, end, opening);
        zero.rows.addAll(List.of(
                deposit("04/03/26", "2,451.08", "BRIGHTWAY LOGISTICS PAYROLL PPD", "ID: 4419920113"),
                deposit("04/30/26", "0.00", "INTEREST EARNED"),
                withdrawal("04/10/26", "-0.00", "CARD VERIFICATION SAMPLE STREAMING"),
                withdrawal("04/11/26", "-0.00", "TRIAL MEMBERSHIP SAMPLE GYM"),
                withdrawal("04/11/26", "-0.00", "TRIAL MEMBERSHIP SAMPLE GYM"),
                withdrawal("04/15/26", "-50.00", "CLEARLINE WIRELESS AUTOPAY")));
        cases.add(zero);

        // Embedded MM/DD purchase dates (deposits and withdrawals only), a store number that looks
        // like a date and fails the 45-day guard, a date-like token on a continuation line (never
        // used), and a Service fees section whose date-like token is never used.
        Statement card = edgeCase("pdf-card-purchases", start, end, opening);
        card.rows.addAll(List.of(
                deposit("04/03/26", "2,451.08", "BRIGHTWAY LOGISTICS PAYROLL PPD", "ID: 4419920113"),
                deposit("04/06/26", "39.99", "REFUND 04/03 SPORTS DEPOT ARLINGTON VA").txn("2026-04-03"),
                withdrawal("04/03/26", "-23.18", "PURCHASE 04/01 CORNER MARKET ARLINGTON VA").txn("2026-04-01"),
                withdrawal("04/14/26", "-4.50", "PURCHASE 04/12 BLUE BEAN CAFE ARLINGTON VA").txn("2026-04-12"),
                withdrawal("04/20/26", "-18.40", "CORNER MART STORE 11/02 ARLINGTON VA"),
                withdrawal("04/24/26", "-64.15", "PURCHASE 04/22 ONLINE ORDER SAMPLE OUTFITTERS",
                        "REF 04/25 SHIPPED").txn("2026-04-22"),
                withdrawal("04/27/26", "-9.99", "PURCHASE 04/27 SAMPLE STREAMING SVC").txn("2026-04-27"),
                fee("04/30/26", "-12.00", "MONTHLY MAINTENANCE FEE 04/30")));
        cases.add(card);

        // A period across a year end: an embedded 12/30 on a January posting belongs to the previous
        // year; 02/29 is valid in neither candidate year; 01/07 on a 01/06 posting would be a year
        // back, over the 45-day guard. Both are ignored.
        Statement year = edgeCase("pdf-year-boundary", LocalDate.of(2026, 12, 10), LocalDate.of(2027, 1, 9), opening);
        year.rows.addAll(List.of(
                deposit("12/31/26", "2,450.00", "BRIGHTWAY LOGISTICS PAYROLL PPD", "ID: 4419920113"),
                withdrawal("12/14/26", "-45.10", "PURCHASE 12/12 HOLLY MARKET ARLINGTON VA").txn("2026-12-12"),
                withdrawal("01/02/27", "-62.75", "PURCHASE 12/30 PARTY SUPPLY ARLINGTON VA").txn("2026-12-30"),
                withdrawal("01/05/27", "-12.00", "PURCHASE 02/29 LEAP STORE ARLINGTON VA"),
                withdrawal("01/06/27", "-30.00", "PURCHASE 01/07 PREPAID SAMPLE TRANSIT"),
                withdrawal("01/08/27", "-8.25", "PURCHASE 01/08 BLUE BEAN CAFE ARLINGTON VA").txn("2027-01-08")));
        cases.add(year);

        // Two check column groups, an odd count (the last line holds one group), a gap marker "*".
        Statement checks = edgeCase("pdf-checks-side-by-side", start, end, opening);
        checks.rows.addAll(List.of(
                deposit("04/03/26", "2,451.08", "BRIGHTWAY LOGISTICS PAYROLL PPD", "ID: 4419920113"),
                check("04/02/26", "1234", "-125.00"),
                check("04/05/26", "1235", "-60.00"),
                check("04/09/26", "1237*", "-310.45"),
                check("04/15/26", "1238", "-45.00"),
                check("04/21/26", "1239", "-1,200.00")));
        cases.add(checks);

        // The withdrawals total is printed 10.00 more negative than its rows (ING-10). The summary
        // agrees with the printed total, so this is the only disagreement.
        Statement mismatch = edgeCase("pdf-section-total-mismatch", start, end, opening);
        mismatch.rows.addAll(List.of(
                deposit("04/03/26", "2,451.08", "BRIGHTWAY LOGISTICS PAYROLL PPD", "ID: 4419920113"),
                withdrawal("04/12/26", "-79.99", "SKYWAVE INTERNET AUTOPAY"),
                withdrawal("04/15/26", "-67.84", "CLEARLINE WIRELESS AUTOPAY"),
                withdrawal("04/18/26", "-133.93", "METRO POWER CO ONLINE PMT")));
        mismatch.printedTotals.put(Section.WITHDRAWAL, mismatch.rowSum(Section.WITHDRAWAL).subtract(money("10.00")));
        mismatch.status = "NEEDS_REVIEW";
        mismatch.warnTotal(Section.WITHDRAWAL, "SECTION_TOTAL_MISMATCH");
        cases.add(mismatch);

        // The summary's deposits amount is 10.00 more than the section total; the total matches the rows.
        Statement disagrees = edgeCase("pdf-summary-disagrees", start, end, opening);
        disagrees.rows.addAll(List.of(
                deposit("04/03/26", "2,451.08", "BRIGHTWAY LOGISTICS PAYROLL PPD", "ID: 4419920113"),
                withdrawal("04/12/26", "-79.99", "SKYWAVE INTERNET AUTOPAY")));
        disagrees.summaryAmounts.put(Section.DEPOSIT, disagrees.rowSum(Section.DEPOSIT).add(money("10.00")));
        disagrees.status = "NEEDS_REVIEW";
        disagrees.warnSummary(Section.DEPOSIT, "SUMMARY_DISAGREES_WITH_TOTAL");
        cases.add(disagrees);

        // Summary only: no sections, every amount 0.00.
        cases.add(edgeCase("pdf-no-activity", start, end, money("1500.00")));

        // The April statement drawn one glyph at a time: the same lines, so the same rows.
        Statement glyphs = new Statement("edge-cases/pdf-glyph-by-glyph.pdf", april.start, april.end, april.opening);
        glyphs.rows.addAll(april.rows);
        glyphs.answerKeyFile = april.answerKeyFile;
        glyphs.glyphByGlyph = true;
        cases.add(glyphs);
        return cases;
    }

    static Statement edgeCase(String name, LocalDate start, LocalDate end, BigDecimal opening) {
        return new Statement("edge-cases/" + name + ".pdf", start, end, opening);
    }

    static Row deposit(String date, String amount, String... lines) {
        return row(Section.DEPOSIT, date, amount, lines);
    }

    static Row withdrawal(String date, String amount, String... lines) {
        return row(Section.WITHDRAWAL, date, amount, lines);
    }

    static Row fee(String date, String amount, String... lines) {
        return row(Section.FEE, date, amount, lines);
    }

    static Row row(Section section, String date, String amount, String... lines) {
        return new Row(LocalDate.parse(date, ROW_DATE), section, amount, List.of(lines), null);
    }

    /** {@code cell} is the check-number cell as printed, gap marker included. */
    static Row check(String date, String cell, String amount) {
        return new Row(LocalDate.parse(date, ROW_DATE), Section.CHECK, amount, List.of(cell), cell.replace("*", ""));
    }

    // ---------------------------------------------------------------- model

    enum Section {
        DEPOSIT("Deposits and other additions"),
        WITHDRAWAL("Withdrawals and other subtractions"),
        CHECK("Checks"),
        FEE("Service fees");

        final String heading;

        Section(String heading) {
            this.heading = heading;
        }

        String totalLabel() {
            return "Total " + heading.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One printed row. {@code lines} are the description lines as printed (for a check, the single
     * check-number cell); {@code txnDate} is the expected transaction date, written by hand where an
     * embedded purchase date applies.
     */
    record Row(LocalDate date, LocalDate txnDate, Section section, String printedAmount, List<String> lines,
               String checkNumber) {

        Row(LocalDate date, Section section, String printedAmount, List<String> lines, String checkNumber) {
            this(date, date, section, printedAmount, List.copyOf(lines), checkNumber);
        }

        Row txn(String isoDate) {
            return new Row(date, LocalDate.parse(isoDate), section, printedAmount, lines, checkNumber);
        }

        BigDecimal amount() {
            return money(printedAmount.replace(",", "").replace("$", ""));
        }
    }

    static final class Statement {
        final String file;
        final LocalDate start;
        final LocalDate end;
        final BigDecimal opening;
        final List<Row> rows = new ArrayList<>();
        final Map<Section, BigDecimal> printedTotals = new EnumMap<>(Section.class);
        final Map<Section, BigDecimal> summaryAmounts = new EnumMap<>(Section.class);
        /** Expected warnings: {anchor key, section, kind}. */
        final List<String[]> warnings = new ArrayList<>();
        String answerKeyFile = "";
        String status = "RECONCILED";
        int rowsPerPage = 9;
        int breakAfterRow = -1;
        String breakNote;
        boolean kerning;
        boolean glyphByGlyph;
        Set<Integer> amountsFirstPages = Set.of();
        Set<Integer> reversedPages = Set.of();

        Statement(String file, LocalDate start, LocalDate end, BigDecimal opening) {
            this.file = file;
            this.start = start;
            this.end = end;
            this.opening = opening;
        }

        BigDecimal rowSum(Section section) {
            return rows.stream().filter(r -> r.section() == section).map(Row::amount)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        }

        BigDecimal closing() {
            return rows.stream().map(Row::amount).reduce(opening, BigDecimal::add);
        }

        boolean inBody(Section section) {
            return rows.stream().anyMatch(r -> r.section() == section);
        }

        /** The section's printed TOTAL; for a section absent from the body, the summary amount (§3.2). */
        BigDecimal total(Section section) {
            if (!inBody(section)) {
                return summary(section);
            }
            return printedTotals.getOrDefault(section, rowSum(section));
        }

        BigDecimal summary(Section section) {
            return summaryAmounts.getOrDefault(section,
                    inBody(section) ? printedTotals.getOrDefault(section, rowSum(section)) : rowSum(section));
        }

        List<Row> bodyRows() {
            return rows.stream().sorted(Comparator.comparing(Row::section)).toList();
        }

        void warn(int bodyRow, Section section, String kind) {
            warnings.add(new String[] {"row:" + bodyRow, section.name(), kind});
            status = "NEEDS_REVIEW";
        }

        void warnTotal(Section section, String kind) {
            warnings.add(new String[] {"total:" + section, section.name(), kind});
        }

        void warnSummary(Section section, String kind) {
            warnings.add(new String[] {"summary:" + section, section.name(), kind});
        }
    }

    enum Role { TEXT, HEADING, DESCRIPTION, AMOUNT }

    /** One string drawn at a position; {@code y} is the baseline from the top of the page. */
    record Item(float x, float y, float size, boolean bold, Role role, String text) {
        float xEnd() {
            return x + width(text, bold, size);
        }
    }

    static final class Page {
        final int number;
        final List<Item> items = new ArrayList<>();
        /** Line kind by baseline, as drawn; an unlabelled line is OTHER. */
        final Map<Float, String> kinds = new TreeMap<>();
        String vectorContent = "";

        Page(int number) {
            this.number = number;
        }
    }

    record Position(int page, float y) {}

    /** A drawn line, as the extractor must read it back. */
    record Line(int number, int page, float y, String text) {}

    // ---------------------------------------------------------------- layout

    /** Lays out one statement. Anchors map "period", "opening", "row:3", "total:DEPOSIT" ... to positions. */
    static final class Layout {
        final Statement s;
        final List<Page> pages = new ArrayList<>();
        final Map<String, Position> anchors = new LinkedHashMap<>();
        Page page;
        float y;
        int unitsOnPage;

        Layout(Statement s) {
            this.s = s;
        }

        List<Page> run() {
            firstPage();
            List<Row> body = s.bodyRows();
            int index = 0;
            for (Section section : Section.values()) {
                List<Row> rows = body.stream().filter(r -> r.section() == section).toList();
                if (rows.isEmpty()) {
                    continue;
                }
                List<List<Integer>> units = new ArrayList<>();
                if (section == Section.CHECK) {
                    for (int i = 0; i < rows.size(); i += 2) {
                        units.add(i + 1 < rows.size() ? List.of(index + i, index + i + 1) : List.of(index + i));
                    }
                } else {
                    for (int i = 0; i < rows.size(); i++) {
                        units.add(List.of(index + i));
                    }
                }
                section(section, body, units);
                index += rows.size();
            }
            footers();
            return pages;
        }

        void firstPage() {
            newPage();
            add(LEFT, 54, 14, true, Role.HEADING, "NORTHFIELD");
            add(LEFT, 80, BODY, false, Role.TEXT, CUSTOMER);
            add(LEFT, 92, BODY, false, Role.TEXT, "100 EXAMPLE WAY");
            add(LEFT, 104, BODY, false, Role.TEXT, "SAMPLETOWN, XX 00000");
            add(LEFT, 128, HEADING, true, Role.HEADING, "Your checking account statement");
            add(LEFT, 142, BODY, false, Role.TEXT, "for " + longDate(s.start) + " to " + longDate(s.end));
            anchor("period", 142);
            label(142, "PERIOD");
            add(LEFT, 154, BODY, false, Role.TEXT, "Account number: " + ACCOUNT_NUMBER);
            label(154, "ACCOUNT");
            add(LEFT, 180, HEADING, true, Role.HEADING, "Account summary");
            y = 196;
            summaryLine("opening", "Beginning balance on " + longDate(s.start), balance(s.opening));
            for (Section section : Section.values()) {
                summaryLine("summary:" + section, section.heading, amount(s.summary(section)));
            }
            summaryLine("closing", "Ending balance on " + longDate(s.end), balance(s.closing()));
            y += 18;
        }

        void summaryLine(String key, String label, String amount) {
            add(LEFT, y, BODY, false, Role.TEXT, label);
            addRight(RIGHT, y, BODY, false, amount);
            anchor(key, y);
            label(y, "SUMMARY");
            y += PITCH;
        }

        void section(Section section, List<Row> body, List<List<Integer>> units) {
            int lines0 = height(body, units.getFirst());
            float need = 14 + 13 + PITCH * (lines0 - 1) + (units.size() == 1 ? 16 : 0);
            if (unitsOnPage >= s.rowsPerPage || y + need > BODY_BOTTOM) {
                nextPage(null, null);
            }
            add(LEFT, y, HEADING, true, Role.HEADING, section.heading);
            label(y, "SECTION_START");
            y += 14;
            columnHeader(section);
            for (int u = 0; u < units.size(); u++) {
                List<Integer> unit = units.get(u);
                boolean last = u == units.size() - 1;
                int lines = height(body, unit);
                float needed = last ? PITCH * lines + 4 : PITCH * (lines - 1);
                if (unitsOnPage >= s.rowsPerPage || y + needed > BODY_BOTTOM) {
                    nextPage(section, null);
                }
                place(section, body, unit);
                if (!last && unit.contains(s.breakAfterRow)) {
                    nextPage(section, s.breakNote);
                }
            }
            add(LEFT, y + 4, BODY, true, Role.HEADING, section.totalLabel());
            addRight(RIGHT, y + 4, BODY, true, total(s.total(section)));
            anchor("total:" + section, y + 4);
            label(y + 4, "TOTAL");
            y += 4 + PITCH + 14;
        }

        void columnHeader(Section section) {
            label(y, "COLUMN_HEADER");
            if (section == Section.CHECK) {
                for (float group : new float[] {LEFT, CHECK_GROUP_2}) {
                    add(group, y, SMALL, true, Role.HEADING, "Date");
                    add(group + CHECK_NUMBER_X - LEFT, y, SMALL, true, Role.HEADING, "Check #");
                    addRight(group == LEFT ? CHECK_AMOUNT_RIGHT : RIGHT, y, SMALL, true, "Amount");
                }
            } else {
                add(LEFT, y, SMALL, true, Role.HEADING, "Date");
                add(DESCRIPTION_X, y, SMALL, true, Role.HEADING, "Description");
                addRight(RIGHT, y, SMALL, true, "Amount");
            }
            y += 13;
        }

        static int height(List<Row> body, List<Integer> unit) {
            return unit.stream().mapToInt(i -> body.get(i).section() == Section.CHECK ? 1 : body.get(i).lines().size())
                    .max().orElseThrow();
        }

        void place(Section section, List<Row> body, List<Integer> unit) {
            if (section == Section.CHECK) {
                for (int g = 0; g < unit.size(); g++) {
                    Row row = body.get(unit.get(g));
                    float x = g == 0 ? LEFT : CHECK_GROUP_2;
                    add(x, y, BODY, false, Role.TEXT, ROW_DATE.format(row.date()));
                    add(x + CHECK_NUMBER_X - LEFT, y, BODY, false, Role.DESCRIPTION, row.lines().getFirst());
                    addRight(g == 0 ? CHECK_AMOUNT_RIGHT : RIGHT, y, BODY, false, row.printedAmount());
                    anchor("row:" + unit.get(g), y);
                }
                label(y, "CHECK_ROW");
                y += PITCH;
            } else {
                Row row = body.get(unit.getFirst());
                add(LEFT, y, BODY, false, Role.TEXT, ROW_DATE.format(row.date()));
                addRight(RIGHT, y, BODY, false, row.printedAmount());
                anchor("row:" + unit.getFirst(), y);
                label(y, "TXN");
                for (int i = 0; i < row.lines().size(); i++) {
                    String line = row.lines().get(i);
                    require(width(line, false, BODY) <= 300, "description line too wide: " + line);
                    add(DESCRIPTION_X, y, BODY, false, Role.DESCRIPTION, line);
                    if (i > 0) {
                        label(y, "CONTINUATION");
                    }
                    y += PITCH;
                }
            }
            unitsOnPage++;
        }

        /** Ends the page. With {@code open}, the section carries on under a "- continued" heading. */
        void nextPage(Section open, String note) {
            if (note != null) {
                // Two row pitches below the last row, at the description column: close enough to look
                // like a wrap, too far to be one (§3.3 rule 4).
                float noteY = y - PITCH + 2 * PITCH;
                require(noteY <= BODY_BOTTOM, "no room for the footer note");
                add(DESCRIPTION_X, noteY, SMALL, false, Role.TEXT, note);
                label(noteY, "PAGE_FOOTER");
            }
            if (open != null) {
                add(LEFT, CONTINUED_Y, SMALL, false, Role.TEXT, "continued on the next page");
                label(CONTINUED_Y, "PAGE_FOOTER");
            }
            newPage();
            add(LEFT, 46, SMALL, false, Role.TEXT, CUSTOMER);
            add(200, 46, SMALL, false, Role.TEXT, "Account # " + ACCOUNT_NUMBER);
            addRight(RIGHT, 46, SMALL, false, longDate(s.start) + " to " + longDate(s.end));
            label(46, "PAGE_HEADER");
            y = 72;
            if (open != null) {
                add(LEFT, y, HEADING, true, Role.HEADING, open.heading + " - continued");
                label(y, "SECTION_CONTINUED");
                y += 14;
                columnHeader(open);
            }
        }

        void newPage() {
            page = new Page(pages.size() + 1);
            pages.add(page);
            addCentered(MARK_HEADER_Y, SMALL, true, MARK);
            // Page 1 has no page header: its top lines are the statement's own (OTHER).
            if (page.number > 1) {
                label(MARK_HEADER_Y, "PAGE_HEADER");
            }
            unitsOnPage = 0;
        }

        void footers() {
            for (Page p : pages) {
                page = p;
                addCentered(PAGE_NUMBER_Y, SMALL, false, "Page " + p.number + " of " + pages.size());
                addCentered(MARK_FOOTER_Y, SMALL, true, MARK);
                label(PAGE_NUMBER_Y, "PAGE_FOOTER");
                label(MARK_FOOTER_Y, "PAGE_FOOTER");
            }
        }

        void anchor(String key, float atY) {
            require(anchors.put(key, new Position(page.number, atY)) == null, "duplicate anchor " + key);
        }

        /** Records the kind of the line drawn at {@code atY} on the current page. */
        void label(float atY, String kind) {
            String previous = page.kinds.put(atY, kind);
            require(previous == null || previous.equals(kind), "line labelled " + previous + " and " + kind);
        }

        void add(float x, float atY, float size, boolean bold, Role role, String text) {
            page.items.add(new Item(x, atY, size, bold, role, text));
        }

        void addRight(float right, float atY, float size, boolean bold, String text) {
            page.items.add(new Item(right - width(text, bold, size), atY, size, bold, Role.AMOUNT, text));
        }

        void addCentered(float atY, float size, boolean bold, String text) {
            page.items.add(new Item((PAGE_WIDTH - width(text, bold, size)) / 2, atY, size, bold,
                    bold ? Role.HEADING : Role.TEXT, text));
        }
    }

    /**
     * The lines the extractor must produce: items grouped by baseline, top to bottom, left to
     * right, joined by one space. Fails if two strings on a line are close enough to merge into one
     * word, or two lines close enough to merge into one line.
     */
    static List<Line> lines(List<Page> pages) {
        List<Line> lines = new ArrayList<>();
        for (Page page : pages) {
            Map<Float, List<Item>> byY = new TreeMap<>();
            for (Item item : page.items) {
                require(!item.text().isEmpty() && !item.text().contains("  ") && item.text().strip().equals(item.text()),
                        "unexpected spacing in " + item.text());
                require(item.x() >= 18 && item.xEnd() <= PAGE_WIDTH - 18, "off the page: " + item.text());
                byY.computeIfAbsent(item.y(), k -> new ArrayList<>()).add(item);
            }
            float previousY = Float.NEGATIVE_INFINITY;
            for (Map.Entry<Float, List<Item>> entry : byY.entrySet()) {
                require(entry.getKey() - previousY >= 6, "lines too close on page " + page.number);
                previousY = entry.getKey();
                List<Item> items = entry.getValue().stream().sorted(Comparator.comparing(Item::x)).toList();
                for (int i = 1; i < items.size(); i++) {
                    require(items.get(i).x() - items.get(i - 1).xEnd() >= MIN_COLUMN_GAP,
                            "strings too close on page " + page.number + ": " + items.get(i).text());
                }
                lines.add(new Line(lines.size() + 1, page.number, entry.getKey(),
                        String.join(" ", items.stream().map(Item::text).toList())));
            }
        }
        return lines;
    }

    // ---------------------------------------------------------------- writing statements

    void writeStatement(Statement s, boolean edgeCase) throws IOException {
        Layout layout = new Layout(s);
        List<Page> pages = layout.run();
        List<Line> lines = lines(pages);
        write(s.file, pdf(pages, s));
        write(base(s.file) + ".lines.txt", linesFile(lines));
        write(base(s.file) + ".labels.txt", labelsFile(pages, lines));
        if (edgeCase) {
            write(base(s.file) + ".expected.csv", expected(s, layout, lines));
        }
        manifest.add(String.join(",", s.file, "boa-checking", "", s.answerKeyFile, s.start.toString(),
                s.end.toString(), s.opening.toPlainString(), s.closing().toPlainString(), s.status));
    }

    static String expected(Statement s, Layout layout, List<Line> lines) {
        Map<Position, Integer> numbers = new LinkedHashMap<>();
        for (Line line : lines) {
            numbers.put(new Position(line.page(), line.y()), line.number());
        }
        java.util.function.Function<String, String> line = key -> {
            Position position = layout.anchors.get(key);
            return position == null ? "" : String.valueOf(numbers.get(position));
        };
        List<String> out = new ArrayList<>(List.of(HEADER_FILE));
        out.add(csv("period_start", line.apply("period"), "", s.start.toString(), "", "", "", "", "", ""));
        out.add(csv("period_end", line.apply("period"), "", s.end.toString(), "", "", "", "", "", ""));
        out.add(csv("opening_balance", line.apply("opening"), "", "", "", balance(s.opening),
                s.opening.toPlainString(), "", "", ""));
        out.add(csv("closing_balance", line.apply("closing"), "", "", "", balance(s.closing()),
                s.closing().toPlainString(), "", "", ""));
        for (Section section : Section.values()) {
            out.add(csv("summary", line.apply("summary:" + section), section.name(), "", "",
                    amount(s.summary(section)), s.summary(section).toPlainString(), "", "", ""));
        }
        for (Section section : Section.values()) {
            out.add(csv("section_total", line.apply("total:" + section), section.name(), "", "",
                    s.inBody(section) ? total(s.total(section)) : "", s.total(section).toPlainString(), "", "", ""));
        }
        List<Row> body = s.bodyRows();
        for (int i = 0; i < body.size(); i++) {
            Row row = body.get(i);
            String description = String.join("\n", row.lines());
            require(!description.contains("\\"), "backslash in description");
            out.add(csv("row", line.apply("row:" + i), row.section().name(), row.date().toString(),
                    row.txnDate().toString(), row.printedAmount(), row.amount().toPlainString(),
                    row.checkNumber() == null ? "" : row.checkNumber(), description.replace("\n", "\\n"), ""));
        }
        for (String[] warning : s.warnings) {
            out.add(csv("warning", line.apply(warning[0]), warning[1], "", "", "", "", "", "", warning[2]));
        }
        return String.join("\n", out) + "\n";
    }

    static String linesFile(List<Line> lines) {
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            text.append(String.format(Locale.ROOT, "p%d L%04d %s%n", line.page(), line.number(), line.text()));
        }
        return text.toString().replace("\r\n", "\n");
    }

    /** One label per drawn line, in the same order as the lines file. Every label must name a drawn line. */
    static String labelsFile(List<Page> pages, List<Line> lines) {
        StringBuilder text = new StringBuilder();
        int labelled = 0;
        for (Line line : lines) {
            Map<Float, String> kinds = pages.get(line.page() - 1).kinds;
            labelled += kinds.containsKey(line.y()) ? 1 : 0;
            text.append(String.format(Locale.ROOT, "p%d L%04d %s%n", line.page(), line.number(),
                    kinds.getOrDefault(line.y(), "OTHER")));
        }
        require(labelled == pages.stream().mapToInt(p -> p.kinds.size()).sum(), "a label names no drawn line");
        return text.toString().replace("\r\n", "\n");
    }

    // ---------------------------------------------------------------- unparsed fixtures

    /** A fictional card statement: text, but none of the checking layout's markers (§1.5). */
    void writeUnrecognizedLayout() throws IOException {
        Statement none = new Statement("edge-cases/pdf-unrecognized-layout.pdf", null, null, null);
        Layout l = new Layout(none);
        l.newPage();
        l.add(LEFT, 54, 14, true, Role.HEADING, "APEX VISA");
        l.add(LEFT, 80, BODY, false, Role.TEXT, CUSTOMER);
        l.add(LEFT, 92, BODY, false, Role.TEXT, "Card ending in 4417");
        l.add(LEFT, 116, BODY, false, Role.TEXT, "Statement closing date 04/30/26");
        l.y = 140;
        l.summaryLine("previous", "Previous balance", "$512.40");
        l.summaryLine("payments", "Payments and credits", "-$512.40");
        l.summaryLine("purchases", "Purchases", "$842.17");
        l.summaryLine("new", "New balance", "$842.17");
        l.summaryLine("minimum", "Minimum payment due 05/25/26", "$35.00");
        l.add(LEFT, 220, HEADING, true, Role.HEADING, "Transactions");
        l.add(LEFT, 234, SMALL, true, Role.HEADING, "Trans");
        l.add(70, 234, SMALL, true, Role.HEADING, "Post");
        l.add(110, 234, SMALL, true, Role.HEADING, "Description");
        l.addRight(RIGHT, 234, SMALL, true, "Amount");
        String[][] txns = {{"04/03", "04/04", "BLUE BEAN CAFE ARLINGTON VA", "4.50"},
                {"04/09", "04/09", "PAYMENT THANK YOU", "-512.40"},
                {"04/12", "04/13", "SAMPLE BOOKSHOP ONLINE", "837.67"}};
        float y = 247;
        for (String[] t : txns) {
            l.add(LEFT, y, BODY, false, Role.TEXT, t[0]);
            l.add(70, y, BODY, false, Role.TEXT, t[1]);
            l.add(110, y, BODY, false, Role.DESCRIPTION, t[2]);
            l.addRight(RIGHT, y, BODY, false, t[3]);
            y += PITCH;
        }
        l.footers();
        write(none.file, pdf(l.pages, none));
        write(base(none.file) + ".lines.txt", linesFile(lines(l.pages)));
        manifest.add(String.join(",", none.file, "none", "UNRECOGNIZED_LAYOUT", "", "", "", "", "", ""));
    }

    /**
     * Vector shapes only, like a scan without a text layer. The mark is printed once, in the header:
     * 17 characters, under the 20 that make a document count as having text (§4.1).
     */
    void writeNoText() throws IOException {
        Statement none = new Statement("edge-cases/pdf-no-text.pdf", null, null, null);
        Layout l = new Layout(none);
        l.newPage();
        StringBuilder shapes = new StringBuilder("0.5 w\n");
        shapes.append(rect(LEFT, 60, 200, 30));
        for (int i = 0; i < 12; i++) {
            float y = PAGE_HEIGHT - (140 + 16 * i);
            shapes.append(f(LEFT)).append(' ').append(f(y)).append(" m ").append(f(RIGHT)).append(' ').append(f(y))
                    .append(" l S\n");
        }
        shapes.append(rect(LEFT, 360, RIGHT - LEFT, 80));
        l.page.vectorContent = shapes.toString();
        write(none.file, pdf(l.pages, none));
        write(base(none.file) + ".lines.txt", linesFile(lines(l.pages)));
        manifest.add(String.join(",", none.file, "none", "NO_TEXT_LAYER", "", "", "", "", "", ""));
    }

    static String rect(float x, float top, float width, float height) {
        return f(x) + " " + f(PAGE_HEIGHT - top - height) + " " + f(width) + " " + f(height) + " re S\n";
    }

    // ---------------------------------------------------------------- PDF writer

    /** Serializes the pages: catalog, page tree, two fonts, then a page and its content per page. */
    static byte[] pdf(List<Page> pages, Statement s) {
        List<String> objects = new ArrayList<>();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            kids.append(i == 0 ? "" : " ").append(5 + 2 * i).append(" 0 R");
        }
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
        for (int i = 0; i < pages.size(); i++) {
            String content = content(pages.get(i), s);
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R "
                    + "/F2 4 0 R >> >> /Contents " + (6 + 2 * i) + " 0 R >>");
            objects.add("<< /Length " + content.length() + " >>\nstream\n" + content + "endstream");
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes("%PDF-1.4\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1));
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(bytes.size());
            bytes.writeBytes(((i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n").getBytes(StandardCharsets.US_ASCII));
        }
        int xref = bytes.size();
        StringBuilder tail = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            tail.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset));
        }
        tail.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        bytes.writeBytes(tail.toString().getBytes(StandardCharsets.US_ASCII));
        return bytes.toByteArray();
    }

    /**
     * One page's content stream. Real PDFs aren't drawn line by line, so on request the amounts come
     * first, or the page is drawn bottom to top, descriptions are split into TJ arrays with kerning,
     * or every glyph is placed on its own. Bold text is drawn twice at the same spot (fake bold).
     */
    static String content(Page page, Statement s) {
        List<Item> items = new ArrayList<>(page.items);
        if (s.reversedPages.contains(page.number)) {
            items.sort(Comparator.comparing(Item::y).reversed());
        }
        if (s.amountsFirstPages.contains(page.number)) {
            items.sort(Comparator.comparing(item -> item.role() != Role.AMOUNT));
        }
        StringBuilder content = new StringBuilder(page.vectorContent);
        for (Item item : items) {
            int times = item.bold() ? 2 : 1;
            for (int t = 0; t < times; t++) {
                if (s.glyphByGlyph) {
                    float x = item.x();
                    for (char c : item.text().toCharArray()) {
                        show(content, item, x, "(" + escape(String.valueOf(c)) + ") Tj");
                        x += width(String.valueOf(c), item.bold(), item.size());
                    }
                } else if (s.kerning && item.role() == Role.DESCRIPTION) {
                    show(content, item, item.x(), kerned(item.text()) + " TJ");
                } else {
                    show(content, item, item.x(), "(" + escape(item.text()) + ") Tj");
                }
            }
        }
        return content.toString();
    }

    static void show(StringBuilder content, Item item, float x, String operator) {
        content.append("BT /").append(item.bold() ? "F2 " : "F1 ").append(f(item.size())).append(" Tf ")
                .append(f(x)).append(' ').append(f(PAGE_HEIGHT - item.y())).append(" Td ").append(operator)
                .append(" ET\n");
    }

    /** A TJ array that tightens the pair after the second letter of every word of four letters or more. */
    static String kerned(String text) {
        StringBuilder array = new StringBuilder("[(");
        String[] words = text.split(" ", -1);
        for (int w = 0; w < words.length; w++) {
            String word = words[w];
            if (w > 0) {
                array.append(' ');
            }
            if (word.length() >= 4) {
                array.append(escape(word.substring(0, 2))).append(") 25 (").append(escape(word.substring(2)));
            } else {
                array.append(escape(word));
            }
        }
        return array.append(")]").toString();
    }

    static String escape(String text) {
        return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    static String f(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    // Advance widths of WinAnsi characters 32-126, in 1/1000 em (the standard-14 AFM metrics).
    static final int[] HELVETICA = {
            278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
            1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
            333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
            556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584};
    static final int[] HELVETICA_BOLD = {
            278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
            975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
            333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
            611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584};

    static float width(String text, boolean bold, float size) {
        int[] widths = bold ? HELVETICA_BOLD : HELVETICA;
        int total = 0;
        for (char c : text.toCharArray()) {
            require(c >= 32 && c < 127, "only printable ASCII is drawn");
            total += widths[c - 32];
        }
        return total * size / 1000f;
    }

    // ---------------------------------------------------------------- formatting and files

    static String longDate(LocalDate date) {
        return LONG_DATE.format(date);
    }

    /** A row or summary amount: "-1,850.00", "2,451.08", "0.00". */
    static String amount(BigDecimal value) {
        return (value.signum() < 0 ? "-" : "") + String.format(Locale.ROOT, "%,.2f", value.abs());
    }

    /** A balance: "$3,200.00", "-$12.00". */
    static String balance(BigDecimal value) {
        return (value.signum() < 0 ? "-$" : "$") + String.format(Locale.ROOT, "%,.2f", value.abs());
    }

    /** A section total, printed like a balance. */
    static String total(BigDecimal value) {
        return balance(value);
    }

    static String csv(String... fields) {
        List<String> quoted = new ArrayList<>();
        for (String field : fields) {
            quoted.add(field.contains(",") || field.contains("\"") ? "\"" + field.replace("\"", "\"\"") + "\"" : field);
        }
        return String.join(",", quoted);
    }

    static String base(String file) {
        return file.substring(0, file.length() - ".pdf".length());
    }

    static void clearOutput() throws IOException {
        for (Path dir : List.of(out, out.resolve("edge-cases"))) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path file : files.filter(SyntheticPdfGenerator::isGenerated).toList()) {
                        Files.delete(file);
                    }
                }
            }
        }
    }

    static boolean isGenerated(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".pdf") || name.endsWith(".csv") || name.endsWith(".lines.txt")
                || name.endsWith(".labels.txt");
    }

    static void write(String relativePath, String content) throws IOException {
        write(relativePath, content.getBytes(StandardCharsets.UTF_8));
    }

    static void write(String relativePath, byte[] content) throws IOException {
        Path path = out.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.write(path, content);
    }

    /** Reads a header-plus-rows CSV; SampleDataGenerator guarantees no field contains a comma or quote. */
    static List<String[]> readCsv(Path path) throws IOException {
        return Files.readAllLines(path).stream().skip(1).map(line -> line.split(",", -1)).toList();
    }

    static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
