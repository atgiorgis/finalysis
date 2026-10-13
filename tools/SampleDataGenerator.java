// Generates fully synthetic bank and credit card statements for tests and demos.
//
// Run from the repo root:  java tools/SampleDataGenerator.java [output-dir]
// The output directory defaults to sample-data; SampleDataTest writes to a temporary directory
// and compares the result with the committed files.
//
// Output is byte-identical on every run: fixed seed, fixed date range, no timestamps,
// locale-independent formatting. All institutions, account numbers, and the employer
// are fictional; merchant strings imitate real-world card descriptors.

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SampleDataGenerator {

    static final LocalDate START = LocalDate.of(2026, 4, 1);
    static final LocalDate END = LocalDate.of(2026, 9, 30);
    static Path out = Path.of("sample-data");
    static final String[] MONTH_ABBREVIATIONS =
            {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    enum Account {
        CHECKING("northfield-checking"),
        SAVINGS("northfield-savings"),
        APEX("apex-visa"),
        SUMMIT("summit-card");

        final String slug;

        Account(String slug) {
            this.slug = slug;
        }
    }

    /**
     * One transaction. {@code amount} follows the app's convention (negative = money out of
     * this account); each statement writer converts it to its institution's own sign rules.
     * {@code postDate} differs from {@code date} only on Apex, which assigns statements by post date.
     */
    record Txn(Account account, LocalDate date, LocalDate postDate, String description, String bankCategory,
               BigDecimal amount, String category, boolean transfer, String anomaly, int seq) {}

    record Period(LocalDate start, LocalDate end, String label) {}

    record Statement(Account account, String file, Period period, BigDecimal opening, BigDecimal closing,
                     List<Txn> rows) {}

    private final Random rng = new Random(20260401L);
    private final List<Txn> txns = new ArrayList<>();
    private int seq;

    public static void main(String[] args) throws IOException {
        if (!Files.isRegularFile(Path.of("tools", "SampleDataGenerator.java"))) {
            System.err.println("Run from the repo root: java tools/SampleDataGenerator.java");
            System.exit(1);
        }
        if (args.length > 0) {
            out = Path.of(args[0]);
        }
        new SampleDataGenerator().run();
    }

    void run() throws IOException {
        generateChecking();
        generateApexSpending();
        generateSummitSpending();

        // Card statements come first: each one decides the next autopay amount in checking.
        List<Statement> apex = cardStatements(Account.APEX, apexCycles(), money("-842.17"), 9,
                "APEX VISA AUTOPAY", "PAYMENT THANK YOU", "Payment");
        List<Statement> summit = cardStatements(Account.SUMMIT, calendarMonths(), money("-512.40"), 25,
                "SUMMIT CARD ONLINE PMT", "ONLINE PAYMENT - THANK YOU", null);
        List<Statement> savings = savingsStatements(money("8000.00"));
        List<Statement> checking = simpleStatements(Account.CHECKING, money("3200.00"));

        List<Statement> statements = new ArrayList<>();
        statements.addAll(checking);
        statements.addAll(savings);
        statements.addAll(apex);
        statements.addAll(summit);

        checkEveryTransactionIsOnOneStatement(statements);
        checkCheckingNeverOverdrawn(checking);
        checkDiningRisesEveryMonth();

        Statement brokenSource = summit.stream()
                .filter(s -> s.period().label().equals("2026-06")).findFirst().orElseThrow();
        Txn removed = brokenSource.rows().stream()
                .filter(t -> t.category().equals("Dining")).findFirst().orElseThrow();
        List<Txn> brokenRows = brokenSource.rows().stream().filter(t -> t != removed).toList();
        Statement broken = new Statement(Account.SUMMIT, "broken/summit-card_2026-06.csv",
                brokenSource.period(), brokenSource.opening(), brokenSource.closing(), brokenRows);

        clearOutput();
        for (Statement s : statements) {
            write(s.file(), render(s.account(), s.opening(), s.rows()));
        }
        write(broken.file(), render(broken.account(), broken.opening(), broken.rows()));
        write("statements-manifest.csv", manifest(Stream.concat(statements.stream(), Stream.of(broken)).toList()));
        write("answer-key.csv", answerKey(statements));

        // OFX versions of the same statements: Northfield checking as OFX 1.x SGML, Apex as QFX (OFX 2.x XML).
        int ofxFiles = 0;
        for (Statement s : checking) {
            write(ofxFile(s, ".ofx"), northfieldOfx(s.period(), s.closing(), ofxRows(s)), WINDOWS_1252);
            ofxFiles++;
        }
        for (Statement s : apex) {
            write(ofxFile(s, ".qfx"), apexQfx(s.period(), s.closing(), ofxRows(s)), StandardCharsets.UTF_8);
            ofxFiles++;
        }
        writeOfxEdgeCases(checking);
        writeCsvEdgeCases(checking, summit);
        writeTransferEdgeCases();

        System.out.printf(Locale.ROOT, "Wrote %d statements, 1 broken statement, %d OFX/QFX statements, %d edge cases, "
                + "%d transfer edge-case statements, %d transactions to %s/%n", statements.size(), ofxFiles,
                OFX_EDGE_CASES + CSV_EDGE_CASES, TRANSFER_EDGE_CASE_STATEMENTS, txns.size(), out);
    }

    // ---------------------------------------------------------------- transactions

    void generateChecking() {
        for (LocalDate payday = LocalDate.of(2026, 4, 3); !payday.isAfter(END); payday = payday.plusDays(14)) {
            add(Account.CHECKING, payday, "BRIGHTWAY LOGISTICS PAYROLL PPD ID: 4419920113",
                    cents(244_750, 245_250), "Income");
        }
        for (YearMonth month : months()) {
            add(Account.CHECKING, month.atDay(1), "PARKSIDE PROPERTY MGMT RENT WEB PMT", money("-1850.00"), "Housing");

            LocalDate transferDay = month.atDay(2);
            addTransfer(Account.CHECKING, transferDay, "ONLINE TRANSFER TO SAVINGS XXXXXX1002", money("-500.00"));
            addTransfer(Account.SAVINGS, transferDay, "ONLINE TRANSFER FROM CHECKING XXXXXX1001", money("500.00"));

            // Planted anomaly: the September internet bill never arrives.
            if (month.getMonthValue() != 9) {
                add(Account.CHECKING, month.atDay(12), "SKYWAVE INTERNET AUTOPAY", money("-79.99"), "Utilities");
            }
            add(Account.CHECKING, month.atDay(15), "CLEARLINE WIRELESS AUTOPAY", cents(6_500, 7_200).negate(), "Utilities");
            add(Account.CHECKING, month.atDay(18), "METRO POWER CO ONLINE PMT", cents(8_000, 14_500).negate(), "Utilities");
            add(Account.CHECKING, month.atDay(22), "CITY OF ARLINGTON WATER UTIL PMT", cents(3_500, 6_000).negate(), "Utilities");
        }
    }

    void generateApexSpending() {
        for (YearMonth month : months()) {
            for (int i = 0; i < 6; i++) {
                String store = i % 2 == 0 ? "SAFEWAY #1234 ARLINGTON VA" : "TRADER JOE S #662 ARLINGTON VA";
                apex(randomDay(month), store, "Groceries", cents(3_500, 14_000), "Groceries", "");
            }
            for (int i = 0; i < 3; i++) {
                apex(randomDay(month), "SHELL OIL 57442 ARLINGTON VA", "Gas/Automotive", cents(3_200, 5_800),
                        "Transportation", "");
            }
            int shoppingTrips = 3 + rng.nextInt(2);
            for (int i = 0; i < shoppingTrips; i++) {
                String store = rng.nextBoolean() ? "AMZN MKTP US*" + amazonReference() : "TARGET 00023481 ARLINGTON VA";
                apex(randomDay(month), store, "Merchandise", cents(2_000, 15_000), "Shopping", "");
            }
            apex(randomDay(month), "CVS/PHARMACY #04512 ARLINGTON VA", "Health Care", cents(1_200, 4_800), "Health", "");

            apex(month.atDay(6), "NETFLIX.COM", "Services", money("15.49"), "Subscriptions", "");
            // Planted anomaly: the music subscription goes up in July.
            boolean raised = month.getMonthValue() >= 7;
            apex(month.atDay(12), "SPOTIFY USA", "Services", money(raised ? "12.99" : "11.99"), "Subscriptions",
                    month.getMonthValue() == 7 ? "price_increase" : "");
            apex(month.atDay(20), "GOOGLE *Google One", "Services", money("2.99"), "Subscriptions", "");
        }

        // Planted anomaly: the same charge posted twice on the same day.
        LocalDate duplicateDay = LocalDate.of(2026, 6, 13);
        apex(duplicateDay, "SAFEWAY #1234 ARLINGTON VA", "Groceries", money("84.12"), "Groceries", "");
        apex(duplicateDay, "SAFEWAY #1234 ARLINGTON VA", "Groceries", money("84.12"), "Groceries", "duplicate");

        // Planted anomaly: one purchase roughly 10x a normal shopping trip.
        apex(LocalDate.of(2026, 8, 22), "BEST BUY 00012345 ARLINGTON VA", "Merchandise", money("899.99"),
                "Shopping", "large_purchase");
    }

    void generateSummitSpending() {
        String[] restaurants = {"SQ *BLUE BEAN CAFE 4412", "CHIPOTLE 2291", "TST* NAKAMURA RAMEN",
                "DOORDASH*THAI BASIL"};
        List<YearMonth> months = months();
        for (int m = 0; m < months.size(); m++) {
            YearMonth month = months.get(m);
            // Planted trend: more dining out, at higher prices, every month.
            for (int i = 0; i < 6 + m; i++) {
                summit(randomDay(month), restaurants[rng.nextInt(restaurants.length)],
                        cents((12 + 2 * m) * 100, (32 + 3 * m) * 100), "Dining");
            }
            for (int i = 0; i < 3; i++) {
                summit(randomDay(month), rng.nextBoolean() ? "UBER *TRIP" : "LYFT *RIDE", cents(900, 2_800),
                        "Transportation");
            }
            for (int i = 0; i < 2; i++) {
                summit(randomDay(month), "GIANT FOOD #0217 ARLINGTON VA", cents(2_000, 7_000), "Groceries");
            }
        }
    }

    // ---------------------------------------------------------------- statements

    /**
     * Builds card statements in order. Each period's autopay pays the previous statement
     * balance in full: a checking debit on {@code payDay}, credited on the card 1-3 days later.
     */
    List<Statement> cardStatements(Account card, List<Period> periods, BigDecimal opening, int payDay,
                                   String checkingDescription, String cardDescription, String bankCategory) {
        List<Statement> statements = new ArrayList<>();
        BigDecimal balance = opening;
        for (Period period : periods) {
            LocalDate paidFromChecking = period.end().withDayOfMonth(payDay);
            if (!paidFromChecking.isAfter(END) && balance.signum() < 0) {
                LocalDate creditedToCard = paidFromChecking.plusDays(1 + rng.nextInt(3));
                require(!creditedToCard.isAfter(period.end()), "card payment falls outside its period");
                BigDecimal payment = balance.negate();
                addTransfer(Account.CHECKING, paidFromChecking, checkingDescription, payment.negate());
                txns.add(new Txn(card, creditedToCard, creditedToCard, cardDescription, bankCategory, payment,
                        "Transfer", true, "", seq++));
            }
            Statement statement = statement(card, period, balance);
            statements.add(statement);
            balance = statement.closing();
        }
        return statements;
    }

    /** Savings earns interest on the month-end balance at a fixed 4.00% APR, computed here, not estimated. */
    List<Statement> savingsStatements(BigDecimal opening) {
        List<Statement> statements = new ArrayList<>();
        BigDecimal balance = opening;
        for (Period period : calendarMonths()) {
            BigDecimal beforeInterest = sum(balance, rowsIn(Account.SAVINGS, period));
            BigDecimal interest = beforeInterest.multiply(new BigDecimal("0.04"))
                    .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_EVEN);
            add(Account.SAVINGS, period.end(), "INTEREST PAYMENT", interest, "Interest");
            Statement statement = statement(Account.SAVINGS, period, balance);
            statements.add(statement);
            balance = statement.closing();
        }
        return statements;
    }

    List<Statement> simpleStatements(Account account, BigDecimal opening) {
        List<Statement> statements = new ArrayList<>();
        BigDecimal balance = opening;
        for (Period period : calendarMonths()) {
            Statement statement = statement(account, period, balance);
            statements.add(statement);
            balance = statement.closing();
        }
        return statements;
    }

    Statement statement(Account account, Period period, BigDecimal opening) {
        List<Txn> rows = rowsIn(account, period);
        String file = "statements/" + account.slug + "_" + period.label() + ".csv";
        return new Statement(account, file, period, opening, sum(opening, rows), rows);
    }

    List<Txn> rowsIn(Account account, Period period) {
        return txns.stream()
                .filter(t -> t.account() == account)
                .filter(t -> !t.postDate().isBefore(period.start()) && !t.postDate().isAfter(period.end()))
                .sorted(Comparator.comparing(Txn::postDate).thenComparingInt(Txn::seq))
                .toList();
    }

    static List<YearMonth> months() {
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth m = YearMonth.from(START); !m.isAfter(YearMonth.from(END)); m = m.plusMonths(1)) {
            months.add(m);
        }
        return months;
    }

    static List<Period> calendarMonths() {
        return months().stream().map(m -> new Period(m.atDay(1), m.atEndOfMonth(), m.toString())).toList();
    }

    /** Apex cycles run the 15th to the 14th; each statement is named for the month it closes. */
    static List<Period> apexCycles() {
        List<Period> cycles = new ArrayList<>();
        for (YearMonth close = YearMonth.from(START); !close.isAfter(YearMonth.from(END).plusMonths(1));
             close = close.plusMonths(1)) {
            cycles.add(new Period(close.minusMonths(1).atDay(15), close.atDay(14), close.toString()));
        }
        return cycles;
    }

    // ---------------------------------------------------------------- consistency checks

    void checkEveryTransactionIsOnOneStatement(List<Statement> statements) {
        long onStatements = statements.stream().mapToLong(s -> s.rows().size()).sum();
        require(onStatements == txns.size(), "some transactions are on no statement, or on two");
    }

    static void checkCheckingNeverOverdrawn(List<Statement> checking) {
        BigDecimal balance = checking.get(0).opening();
        for (Statement s : checking) {
            for (Txn t : s.rows()) {
                balance = balance.add(t.amount());
                require(balance.signum() >= 0, "checking goes negative on " + t.date());
            }
        }
    }

    void checkDiningRisesEveryMonth() {
        BigDecimal previous = BigDecimal.ZERO;
        for (YearMonth month : months()) {
            BigDecimal total = txns.stream()
                    .filter(t -> t.category().equals("Dining") && YearMonth.from(t.date()).equals(month))
                    .map(t -> t.amount().negate())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            require(total.compareTo(previous) > 0, "dining spend does not rise in " + month);
            previous = total;
        }
    }

    // ---------------------------------------------------------------- file formats

    static String render(Account account, BigDecimal opening, List<Txn> rows) {
        List<String> lines = new ArrayList<>();
        switch (account) {
            case CHECKING, SAVINGS -> {
                // Northfield: separate Debit/Credit columns (both positive) and a running balance.
                lines.add("Date,Description,Debit,Credit,Balance");
                BigDecimal balance = opening;
                for (Txn t : rows) {
                    balance = balance.add(t.amount());
                    String debit = t.amount().signum() < 0 ? format(t.amount().negate()) : "";
                    String credit = t.amount().signum() > 0 ? format(t.amount()) : "";
                    lines.add(String.join(",", northfieldDate(t.date()), t.description(), debit, credit,
                            format(balance)));
                }
            }
            case APEX -> {
                // Apex: purchases positive, payments negative.
                lines.add("Transaction Date,Post Date,Description,Category,Amount");
                for (Txn t : rows) {
                    lines.add(String.join(",", t.date().toString(), t.postDate().toString(), t.description(),
                            t.bankCategory(), format(t.amount().negate())));
                }
            }
            case SUMMIT -> {
                // Summit: purchases negative, payments positive.
                lines.add("Trans. Date,Merchant Name,Amount");
                for (Txn t : rows) {
                    lines.add(String.join(",", summitDate(t.date()), t.description(), format(t.amount())));
                }
            }
        }
        return String.join("\n", lines) + "\n";
    }

    static String manifest(List<Statement> statements) {
        List<String> lines = new ArrayList<>();
        lines.add("file,account,period_start,period_end,opening_balance,closing_balance");
        for (Statement s : statements) {
            lines.add(String.join(",", s.file(), s.account().slug, s.period().start().toString(),
                    s.period().end().toString(), format(s.opening()), format(s.closing())));
        }
        return String.join("\n", lines) + "\n";
    }

    static String answerKey(List<Statement> statements) {
        List<String> lines = new ArrayList<>();
        lines.add("file,account,date,description,amount,expected_category,is_transfer,anomaly");
        for (Statement s : statements) {
            for (Txn t : s.rows()) {
                lines.add(String.join(",", s.file(), s.account().slug, t.date().toString(), t.description(),
                        format(t.amount()), t.category(), String.valueOf(t.transfer()), t.anomaly()));
            }
        }
        return String.join("\n", lines) + "\n";
    }

    static String northfieldDate(LocalDate d) {
        return String.format(Locale.ROOT, "%02d/%02d/%04d", d.getMonthValue(), d.getDayOfMonth(), d.getYear());
    }

    static String summitDate(LocalDate d) {
        return String.format(Locale.ROOT, "%02d-%s-%04d", d.getDayOfMonth(),
                MONTH_ABBREVIATIONS[d.getMonthValue() - 1], d.getYear());
    }

    static String format(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    // ---------------------------------------------------------------- OFX formats

    static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    static final int OFX_EDGE_CASES = 4;
    /** Fictional account numbers; only their last four digits match the accounts' last_four. */
    static final String NORTHFIELD_ROUTING = "999000111";
    static final String NORTHFIELD_CHECKING_ID = "0047391001";
    static final String NORTHFIELD_SAVINGS_ID = "0047391002";
    static final String APEX_CARD_ID = "4999000011112001";
    /** OFX 1.x limits NAME to 32 characters; longer descriptions continue in MEMO. */
    static final int OFX_NAME_MAX = 32;

    /** One STMTTRN. {@code memo} is "" when empty; {@code transferTo} is a BANKACCTTO account id or null. */
    record OfxRow(String type, LocalDate posted, LocalDate user, BigDecimal amount, String fitId, String name,
                  String memo, String transferTo) {}

    static String ofxFile(Statement s, String extension) {
        return "ofx/" + s.account().slug + "_" + s.period().label() + extension;
    }

    static List<OfxRow> ofxRows(Statement s) {
        boolean card = s.account() == Account.APEX;
        List<OfxRow> rows = new ArrayList<>();
        for (Txn t : s.rows()) {
            String type;
            if (card) {
                type = t.amount().signum() < 0 ? "DEBIT" : "PAYMENT";
            } else if (t.transfer()) {
                type = "XFER";
            } else if (t.amount().signum() > 0) {
                type = t.description().contains("PAYROLL") ? "DIRECTDEP" : "CREDIT";
            } else {
                type = "DEBIT";
            }
            String[] nameAndMemo = nameAndMemo(t.description());
            String fitId = (card ? "AX" : "NF") + t.postDate().toString().replace("-", "") + String.format(Locale.ROOT, "%05d", t.seq());
            rows.add(new OfxRow(type, t.postDate(), card ? t.date() : null, t.amount(), fitId, nameAndMemo[0],
                    nameAndMemo[1], null));
        }
        return rows;
    }

    /** Splits at the last space within NAME's limit, so NAME + " " + MEMO is the description again. */
    static String[] nameAndMemo(String description) {
        if (description.length() <= OFX_NAME_MAX) {
            return new String[] {description, ""};
        }
        int split = description.lastIndexOf(' ', OFX_NAME_MAX);
        require(split > 0, "description cannot be split into NAME and MEMO: " + description);
        return new String[] {description.substring(0, split), description.substring(split + 1)};
    }

    /** OFX 1.x SGML with CRLF line endings, unclosed leaf tags and an empty MEMO for short descriptions. */
    static String northfieldOfx(Period period, BigDecimal closing, List<OfxRow> rows) {
        List<String> lines = new ArrayList<>(List.of(
                "OFXHEADER:100", "DATA:OFXSGML", "VERSION:102", "SECURITY:NONE", "ENCODING:USASCII", "CHARSET:1252",
                "COMPRESSION:NONE", "OLDFILEUID:NONE", "NEWFILEUID:NONE", "",
                "<OFX>", "<SIGNONMSGSRSV1>", "<SONRS>", "<STATUS>", "<CODE>0", "<SEVERITY>INFO", "</STATUS>",
                "<DTSERVER>" + ofxDate(period.end().plusDays(1)) + "080000", "<LANGUAGE>ENG",
                "<FI>", "<ORG>NORTHFIELD", "<FID>9901", "</FI>", "</SONRS>", "</SIGNONMSGSRSV1>",
                "<BANKMSGSRSV1>", "<STMTTRNRS>", "<TRNUID>1", "<STATUS>", "<CODE>0", "<SEVERITY>INFO", "</STATUS>",
                "<STMTRS>", "<CURDEF>USD",
                "<BANKACCTFROM>", "<BANKID>" + NORTHFIELD_ROUTING, "<ACCTID>" + NORTHFIELD_CHECKING_ID,
                "<ACCTTYPE>CHECKING", "</BANKACCTFROM>",
                "<BANKTRANLIST>", "<DTSTART>" + ofxDate(period.start()), "<DTEND>" + ofxDate(period.end())));
        for (OfxRow row : rows) {
            lines.add("<STMTTRN>");
            lines.add("<TRNTYPE>" + row.type());
            lines.add("<DTPOSTED>" + ofxDate(row.posted()) + "120000[-5:EST]");
            lines.add("<TRNAMT>" + format(row.amount()));
            lines.add("<FITID>" + row.fitId());
            lines.add("<NAME>" + sgmlText(row.name()));
            lines.add("<MEMO>" + sgmlText(row.memo()));
            if (row.transferTo() != null) {
                lines.addAll(List.of("<BANKACCTTO>", "<BANKID>" + NORTHFIELD_ROUTING, "<ACCTID>" + row.transferTo(),
                        "<ACCTTYPE>SAVINGS", "</BANKACCTTO>"));
            }
            lines.add("</STMTTRN>");
        }
        lines.addAll(List.of("</BANKTRANLIST>", "<LEDGERBAL>", "<BALAMT>" + format(closing),
                "<DTASOF>" + ofxDate(period.end()), "</LEDGERBAL>", "</STMTRS>", "</STMTTRNRS>", "</BANKMSGSRSV1>",
                "</OFX>"));
        return String.join("\r\n", lines) + "\r\n";
    }

    /** QFX: OFX 2.x XML with closed tags and an Intuit INTU.BID; MEMO is omitted when empty. */
    static String apexQfx(Period period, BigDecimal closing, List<OfxRow> rows) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n")
                .append("<?OFX OFXHEADER=\"200\" VERSION=\"220\" SECURITY=\"NONE\" OLDFILEUID=\"NONE\" NEWFILEUID=\"NONE\"?>\n")
                .append("<OFX>\n")
                .append("  <SIGNONMSGSRSV1>\n    <SONRS>\n")
                .append("      <STATUS><CODE>0</CODE><SEVERITY>INFO</SEVERITY></STATUS>\n")
                .append("      <DTSERVER>").append(ofxDate(period.end().plusDays(1))).append("080000.000</DTSERVER>\n")
                .append("      <LANGUAGE>ENG</LANGUAGE>\n")
                .append("      <FI><ORG>APEX</ORG><FID>7702</FID></FI>\n")
                .append("      <INTU.BID>7702</INTU.BID>\n")
                .append("    </SONRS>\n  </SIGNONMSGSRSV1>\n")
                .append("  <CREDITCARDMSGSRSV1>\n    <CCSTMTTRNRS>\n      <TRNUID>1</TRNUID>\n")
                .append("      <STATUS><CODE>0</CODE><SEVERITY>INFO</SEVERITY></STATUS>\n")
                .append("      <CCSTMTRS>\n        <CURDEF>USD</CURDEF>\n")
                .append("        <CCACCTFROM><ACCTID>").append(APEX_CARD_ID).append("</ACCTID></CCACCTFROM>\n")
                .append("        <BANKTRANLIST>\n")
                .append("          <DTSTART>").append(ofxDate(period.start())).append("</DTSTART>\n")
                .append("          <DTEND>").append(ofxDate(period.end())).append("</DTEND>\n");
        for (OfxRow row : rows) {
            xml.append("          <STMTTRN>\n")
                    .append("            <TRNTYPE>").append(row.type()).append("</TRNTYPE>\n")
                    .append("            <DTPOSTED>").append(ofxDate(row.posted())).append("000000.000</DTPOSTED>\n")
                    .append("            <DTUSER>").append(ofxDate(row.user())).append("000000.000</DTUSER>\n")
                    .append("            <TRNAMT>").append(format(row.amount())).append("</TRNAMT>\n")
                    .append("            <FITID>").append(row.fitId()).append("</FITID>\n")
                    .append("            <NAME>").append(sgmlText(row.name())).append("</NAME>\n");
            if (!row.memo().isEmpty()) {
                xml.append("            <MEMO>").append(sgmlText(row.memo())).append("</MEMO>\n");
            }
            xml.append("          </STMTTRN>\n");
        }
        xml.append("        </BANKTRANLIST>\n")
                .append("        <LEDGERBAL><BALAMT>").append(format(closing)).append("</BALAMT>")
                .append("<DTASOF>").append(ofxDate(period.end())).append("</DTASOF></LEDGERBAL>\n")
                .append("      </CCSTMTRS>\n    </CCSTMTTRNRS>\n  </CREDITCARDMSGSRSV1>\n</OFX>\n");
        return xml.toString();
    }

    /** Hand-built OFX files for parser and import edge cases; not part of the answer key. */
    void writeOfxEdgeCases(List<Statement> checking) throws IOException {
        Period april = new Period(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), "2026-04");
        LocalDate day = LocalDate.of(2026, 4, 10);

        // TRNTYPE contradicts the sign: a DEBIT that adds money and a CREDIT that removes it.
        write("edge-cases/ofx-trntype-contradicts-sign.ofx", northfieldOfx(april, money("1015.00"), List.of(
                new OfxRow("DEBIT", day, null, money("25.00"), "EDGE0001", "MERCHANT ADJUSTMENT", "", null),
                new OfxRow("CREDIT", day, null, money("-10.00"), "EDGE0002", "MONTHLY SERVICE FEE", "", null),
                new OfxRow("DEBIT", day, null, money("-0.00"), "EDGE0003", "FEE WAIVER", "ZERO AMOUNT", null))),
                WINDOWS_1252);

        // LEDGERBAL disagrees with the May checking statement's rows by 10.00.
        Statement may = checking.stream().filter(s -> s.period().label().equals("2026-05")).findFirst().orElseThrow();
        write("edge-cases/ofx-ledgerbal-mismatch.ofx",
                northfieldOfx(may.period(), may.closing().add(money("10.00")), ofxRows(may)), WINDOWS_1252);

        // A transfer to the user's own savings account, with the full target account number in BANKACCTTO.
        write("edge-cases/ofx-bankacctto-transfer.ofx", northfieldOfx(april, money("2500.00"), List.of(
                new OfxRow("XFER", LocalDate.of(2026, 4, 2), null, money("-500.00"), "EDGE0101",
                        "ONLINE TRANSFER TO SAVINGS", "XXXXXX1002", NORTHFIELD_SAVINGS_ID))),
                WINDOWS_1252);

        // XXE: a DOCTYPE with an external entity. The reader must reject it before any entity is resolved.
        String qfx = apexQfx(april, money("-15.49"), List.of(
                new OfxRow("DEBIT", day, day, money("-15.49"), "EDGE0201", "NETFLIX.COM", "", null)));
        String xxe = qfx.replace("<?OFX", "<!DOCTYPE OFX [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n<?OFX")
                .replace("<NAME>NETFLIX.COM</NAME>", "<NAME>&xxe;</NAME>");
        write("edge-cases/ofx-xxe.ofx", xxe, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- CSV edge cases

    static final int CSV_EDGE_CASES = 9;

    /**
     * Hand-built CSV files for parser and import edge cases; not part of the answer key. Written
     * last and without the random generator, so adding one never changes the other files.
     */
    void writeCsvEdgeCases(List<Statement> checking, List<Statement> summit) throws IOException {
        String summitHeader = "Trans. Date,Merchant Name,Amount";

        // Summit layout, UTF-8 with a BOM and CRLF line endings: a quoted comma, a line break inside a
        // quoted cell, doubled quotes, a non-ASCII merchant and a cell with surrounding spaces.
        write("edge-cases/csv-quoted-fields.csv", "\uFEFF" + csvLines("\r\n", summitHeader,
                csvRow("03-Apr-2026", "BLUE BEAN CAFE, ARLINGTON VA", "-4.50"),
                csvRow("04-Apr-2026", "NAKAMURA RAMEN\nTIP ADJUSTMENT", "-12.00"),
                csvRow("05-Apr-2026", "THE \"GOOD\" DELI", "-9.25"),
                csvRow("06-Apr-2026", "CAFÉ MÜNCHEN 東京", "-18.40"),
                csvRow("07-Apr-2026", "  PADDED MERCHANT  ", "-3.00")));

        // SEC-11: formula-leading descriptions are data and are stored unchanged.
        write("edge-cases/csv-formula-descriptions.csv", csvLines("\n", summitHeader,
                csvRow("08-Apr-2026", "=SUM(A1:A9)", "-1.00"),
                csvRow("08-Apr-2026", "+1 PREMIUM CAFE", "-2.00"),
                csvRow("08-Apr-2026", "-CANCELLED ORDER", "-3.00"),
                csvRow("08-Apr-2026", "@HANDLE COFFEE", "-4.00")));

        // Two genuine identical purchases on one day, plus near-duplicates differing only in amount.
        write("edge-cases/csv-duplicates.csv", csvLines("\n", summitHeader,
                csvRow("10-Apr-2026", "CHIPOTLE 2291", "-14.25"),
                csvRow("10-Apr-2026", "CHIPOTLE 2291", "-14.25"),
                csvRow("10-Apr-2026", "CHIPOTLE 2291", "-14.52"),
                csvRow("10-Apr-2026", "CHIPOTLE 2291", "-14.26")));

        // Apex layout (purchases positive): a purchase and its refund.
        write("edge-cases/csv-card-refund.csv", csvLines("\n", "Transaction Date,Post Date,Description,Category,Amount",
                csvRow("2026-04-11", "2026-04-12", "TARGET 00023481 ARLINGTON VA", "Merchandise", "120.00"),
                csvRow("2026-04-18", "2026-04-19", "TARGET 00023481 ARLINGTON VA", "Merchandise", "-120.00")));

        // The April Summit statement, newest first.
        Statement april = summit.stream().filter(s -> s.period().label().equals("2026-04")).findFirst().orElseThrow();
        List<Txn> newestFirst = new ArrayList<>(april.rows());
        Collections.reverse(newestFirst);
        write("edge-cases/csv-newest-first.csv", render(Account.SUMMIT, april.opening(), newestFirst));

        // Northfield layout in windows-1252: É is 0xC9 and the curly apostrophe 0x92, invalid as UTF-8.
        write("edge-cases/csv-windows-1252.csv", csvLines("\r\n", "Date,Description,Debit,Credit,Balance",
                csvRow("04/05/2026", "CAFÉ RÉSUMÉ ARLINGTON VA", "12.75", "", "987.25"),
                csvRow("04/06/2026", "JOSÉ’S TACOS", "8.50", "", "978.75")), WINDOWS_1252);

        // A checking export for 04-15 to 05-14, overlapping the April and May statements.
        LocalDate from = LocalDate.of(2026, 4, 15);
        LocalDate to = LocalDate.of(2026, 5, 14);
        List<Txn> aprilAndMay = Stream.concat(checking.get(0).rows().stream(), checking.get(1).rows().stream()).toList();
        BigDecimal opening = sum(checking.get(0).opening(),
                aprilAndMay.stream().filter(t -> t.postDate().isBefore(from)).toList());
        List<Txn> overlap = aprilAndMay.stream()
                .filter(t -> !t.postDate().isBefore(from) && !t.postDate().isAfter(to)).toList();
        write("edge-cases/csv-northfield-checking_2026-04-15_2026-05-14.csv", render(Account.CHECKING, opening, overlap));

        // The April checking statement below a summary block, as some banks export it: four
        // preamble lines (three with a comma, so they look like a short two-column table) and a
        // blank line before the header.
        Statement aprilChecking = checking.get(0);
        write("edge-cases/csv-preamble.csv", String.join("\n",
                "Northfield Bank Account Activity",
                "Account number,XXXXXX1001",
                "Statement period,04/01/2026 to 04/30/2026",
                "Opening balance," + format(aprilChecking.opening()),
                "") + "\n" + render(Account.CHECKING, aprilChecking.opening(), aprilChecking.rows()));

        write("edge-cases/csv-summary-preamble-balance-row.csv", summaryPreambleBalanceRow());
    }

    // ---------------------------------------------------------------- transfer edge cases

    static final int TRANSFER_EDGE_CASE_STATEMENTS = 3;
    static final String TRANSFERS_DIR = "edge-cases/transfers/";

    /**
     * One row of the transfer fixtures. {@code key} names the row; {@code expected} is LINKED (with
     * the counterpart's key), REVIEW (with the review group's name), ONE_SIDED (detection leaves it
     * alone; a test marks it one-sided) or NONE.
     */
    record TransferEdgeRow(String key, Account account, LocalDate date, String description, String amount,
                           String expected, String counterpart, String scenario) {}

    /**
     * May 2026 statements for Northfield checking, Northfield savings and Summit in their usual
     * layouts, one transfer scenario per amount (docs/plans/phase-3c.md §7), plus
     * {@code transfers-expected.csv}: each row's file, line (the header is line 1), date, amount
     * and expected detection outcome. Written last and without the random generator, so the other
     * files are unchanged.
     */
    void writeTransferEdgeCases() throws IOException {
        List<TransferEdgeRow> rows = List.of(
                tr("c-payroll", Account.CHECKING, 1, "HARBORLINE FREIGHT PAYROLL DIRECT DEP", "2310.55", "NONE", "", "income; no counterpart"),
                tr("c-savings-same-day", Account.CHECKING, 4, "ONLINE TRANSFER TO SAVINGS", "-300.00", "LINKED", "s-savings-same-day", "checking to savings, same day"),
                tr("c-card-3", Account.CHECKING, 6, "SUMMIT CARD ONLINE PMT", "-410.25", "LINKED", "k-card-3", "card payment, card posts 3 days later"),
                tr("c-card-5", Account.CHECKING, 8, "SUMMIT CARD ONLINE PMT", "-133.10", "LINKED", "k-card-5", "card payment 5 days later: inside the window"),
                tr("c-card-6", Account.CHECKING, 8, "SUMMIT CARD ONLINE PMT", "-144.20", "NONE", "", "card payment 6 days later: outside the window"),
                tr("c-card-before", Account.CHECKING, 12, "SUMMIT CARD ONLINE PMT", "-95.60", "LINKED", "k-card-before", "card credits the day before checking"),
                tr("c-250-first", Account.CHECKING, 15, "ONLINE TRANSFER TO SAVINGS", "-250.00", "LINKED", "s-250-first", "two equal transfers: date order"),
                tr("c-250-second", Account.CHECKING, 17, "ONLINE TRANSFER TO SAVINGS", "-250.00", "LINKED", "s-250-second", "two equal transfers: date order"),
                tr("c-200", Account.CHECKING, 20, "ONLINE TRANSFER", "-200.00", "REVIEW", "G1", "one out, two possible ins"),
                tr("c-hardware", Account.CHECKING, 22, "CORNER HARDWARE DEBIT CARD PURCHASE", "-42.00", "LINKED", "k-refund", "documented false positive: purchase and unrelated card refund"),
                tr("c-cash-advance", Account.CHECKING, 25, "SUMMIT CASH ADVANCE DEPOSIT", "75.00", "NONE", "", "card to checking: never auto-linked"),
                tr("c-one-sided", Account.CHECKING, 27, "ONLINE PMT TO BRIGHTSTAR CARD", "-160.00", "ONE_SIDED", "", "payment to an account not in the app"),
                tr("s-savings-same-day", Account.SAVINGS, 4, "ONLINE TRANSFER FROM CHECKING", "300.00", "LINKED", "c-savings-same-day", "checking to savings, same day"),
                tr("s-250-first", Account.SAVINGS, 15, "ONLINE TRANSFER FROM CHECKING", "250.00", "LINKED", "c-250-first", "two equal transfers: date order"),
                tr("s-250-second", Account.SAVINGS, 17, "ONLINE TRANSFER FROM CHECKING", "250.00", "LINKED", "c-250-second", "two equal transfers: date order"),
                tr("s-200", Account.SAVINGS, 20, "ONLINE TRANSFER FROM CHECKING", "200.00", "REVIEW", "G1", "one out, two possible ins"),
                tr("s-interest", Account.SAVINGS, 31, "INTEREST EARNED", "0.42", "NONE", "", "interest; no counterpart"),
                tr("k-coffee", Account.SUMMIT, 3, "SQ *CORNER BEAN CAFE", "-4.50", "NONE", "", "purchase"),
                tr("k-card-3", Account.SUMMIT, 9, "ONLINE PAYMENT - THANK YOU", "410.25", "LINKED", "c-card-3", "card payment, card posts 3 days later"),
                tr("k-card-before", Account.SUMMIT, 11, "ONLINE PAYMENT - THANK YOU", "95.60", "LINKED", "c-card-before", "card credits the day before checking"),
                tr("k-card-5", Account.SUMMIT, 13, "ONLINE PAYMENT - THANK YOU", "133.10", "LINKED", "c-card-5", "card payment 5 days later: inside the window"),
                tr("k-card-6", Account.SUMMIT, 14, "ONLINE PAYMENT - THANK YOU", "144.20", "NONE", "", "card payment 6 days later: outside the window"),
                tr("k-200", Account.SUMMIT, 21, "ONLINE PAYMENT - THANK YOU", "200.00", "REVIEW", "G1", "one out, two possible ins"),
                tr("k-refund", Account.SUMMIT, 24, "CORNER HARDWARE REFUND", "42.00", "LINKED", "c-hardware", "documented false positive: purchase and unrelated card refund"),
                tr("k-cash-advance", Account.SUMMIT, 25, "CASH ADVANCE", "-75.00", "NONE", "", "card to checking: never auto-linked"));

        record Located(String file, int line) {}
        Map<String, Located> located = new HashMap<>();
        List<String> expected = new ArrayList<>(List.of("file,line,date,amount,expected,counterpart,scenario"));
        Map<Account, BigDecimal> openings = Map.of(
                Account.CHECKING, money("5000.00"), Account.SAVINGS, money("6000.00"), Account.SUMMIT, money("-120.00"));
        for (Account account : List.of(Account.CHECKING, Account.SAVINGS, Account.SUMMIT)) {
            String file = TRANSFERS_DIR + "transfers-" + account.slug + "_2026-05.csv";
            List<TransferEdgeRow> mine = rows.stream().filter(r -> r.account() == account).toList();
            List<Txn> txnRows = new ArrayList<>();
            for (TransferEdgeRow r : mine) {
                require(txnRows.isEmpty() || !r.date().isBefore(txnRows.getLast().date()), "transfer rows out of date order");
                located.put(r.key(), new Located(file, txnRows.size() + 2));
                txnRows.add(new Txn(account, r.date(), r.date(), r.description(), null, money(r.amount()), "", false, "", 0));
            }
            write(file, render(account, openings.get(account), txnRows));
        }
        for (TransferEdgeRow r : rows) {
            Located at = located.get(r.key());
            String counterpart = switch (r.expected()) {
                case "LINKED" -> {
                    TransferEdgeRow other = rows.stream().filter(o -> o.key().equals(r.counterpart())).findFirst().orElseThrow();
                    require(other.counterpart().equals(r.key()) && money(other.amount()).equals(money(r.amount()).negate()),
                            "linked transfer rows must name each other and be equal and opposite: " + r.key());
                    Located o = located.get(other.key());
                    yield o.file().substring(TRANSFERS_DIR.length()) + ":" + o.line();
                }
                default -> r.counterpart();
            };
            expected.add(csvRow(at.file().substring(TRANSFERS_DIR.length()), String.valueOf(at.line()),
                    r.date().toString(), r.amount(), r.expected(), counterpart, r.scenario()));
        }
        write(TRANSFERS_DIR + "transfers-expected.csv", String.join("\n", expected) + "\n");
    }

    static TransferEdgeRow tr(String key, Account account, int mayDay, String description, String amount,
                              String expected, String counterpart, String scenario) {
        return new TransferEdgeRow(key, account, LocalDate.of(2026, 5, mayDay), description, amount, expected,
                counterpart, scenario);
    }

    /**
     * A checking export in the layout of a fictional Larkspur Credit Union: a 3-column summary block
     * (empty middle header, four balance and total rows) and a blank line above the 4-column table,
     * whose first row is a balance-only row (date, text and running balance, no amount). Amounts
     * are signed, with quoted thousands. The summary block is five consistent records, so a
     * "first run of five" table detection picks it instead of the table.
     */
    static String summaryPreambleBalanceRow() {
        BigDecimal opening = money("2415.30");
        record Row(LocalDate date, String description, BigDecimal amount) {}
        List<Row> rows = List.of(
                new Row(LocalDate.of(2026, 5, 2), "MAPLEWOOD GROCERY #214 DEBIT CARD PURCHASE", money("-86.42")),
                new Row(LocalDate.of(2026, 5, 5), "HARBORLINE FREIGHT PAYROLL DIRECT DEP", money("2310.55")),
                new Row(LocalDate.of(2026, 5, 6), "ONLINE TRANSFER TO SAVINGS", money("-1000.00")),
                new Row(LocalDate.of(2026, 5, 9), "CEDAR HILL APARTMENTS RENT, WEB PMT", money("-1425.00")),
                new Row(LocalDate.of(2026, 5, 12), "BRIGHTSPARK ELECTRIC ONLINE PMT", money("-112.37")),
                new Row(LocalDate.of(2026, 5, 15), "CORNER BEAN CAFE DEBIT CARD PURCHASE", money("-6.75")),
                new Row(LocalDate.of(2026, 5, 19), "HARBORLINE FREIGHT PAYROLL DIRECT DEP", money("2310.55")),
                new Row(LocalDate.of(2026, 5, 22), "NORTHGATE FUEL #88 DEBIT CARD PURCHASE", money("-48.19")),
                new Row(LocalDate.of(2026, 5, 26), "ORBITNET INTERNET AUTOPAY", money("-64.99")),
                new Row(LocalDate.of(2026, 5, 28), "INTEREST EARNED", money("0.84")));
        LocalDate first = LocalDate.of(2026, 5, 1);
        LocalDate last = rows.getLast().date();
        BigDecimal credits = rows.stream().map(Row::amount).filter(a -> a.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal debits = rows.stream().map(Row::amount).filter(a -> a.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal closing = opening.add(credits).add(debits);

        List<String> lines = new ArrayList<>(List.of(
                csvRow("Description", "", "Summary Amt."),
                csvRow("Beginning balance as of " + northfieldDate(first), "", thousands(opening)),
                csvRow("Total credits", "", thousands(credits)),
                csvRow("Total debits", "", thousands(debits)),
                csvRow("Ending balance as of " + northfieldDate(last), "", thousands(closing)),
                "",
                csvRow("Date", "Description", "Amount", "Running Bal."),
                csvRow(northfieldDate(first), "Beginning balance as of " + northfieldDate(first), "", thousands(opening))));
        BigDecimal balance = opening;
        for (Row row : rows) {
            balance = balance.add(row.amount());
            lines.add(csvRow(northfieldDate(row.date()), row.description(), thousands(row.amount()), thousands(balance)));
        }
        require(balance.compareTo(closing) == 0, "summary preamble: running balance must end at the closing balance");
        return String.join("\n", lines) + "\n";
    }

    /** Two decimals with comma thousands separators, e.g. -1,425.00 (quoted by csvCell). */
    static String thousands(BigDecimal amount) {
        return String.format(Locale.ROOT, "%,.2f", amount.setScale(2, RoundingMode.UNNECESSARY));
    }

    static String csvLines(String lineBreak, String header, String... rows) {
        return header + lineBreak + String.join(lineBreak, rows) + lineBreak;
    }

    static String csvRow(String... cells) {
        return Stream.of(cells).map(SampleDataGenerator::csvCell).collect(Collectors.joining(","));
    }

    /** RFC 4180: cells with a comma, quote or line break are quoted, quotes doubled. */
    static String csvCell(String cell) {
        boolean quote = cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r");
        return quote ? "\"" + cell.replace("\"", "\"\"") + "\"" : cell;
    }

    static String ofxDate(LocalDate d) {
        return d.toString().replace("-", "");
    }

    static String sgmlText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------------------------------------------------------- output

    static void clearOutput() throws IOException {
        for (String dir : List.of("statements", "broken", "ofx", "edge-cases", "edge-cases/transfers")) {
            Path path = out.resolve(dir);
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.list(path)) {
                    for (Path file : files.filter(SampleDataGenerator::isGenerated).toList()) {
                        Files.delete(file);
                    }
                }
            }
        }
    }

    static boolean isGenerated(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".csv") || name.endsWith(".ofx") || name.endsWith(".qfx");
    }

    static void write(String relativePath, String content) throws IOException {
        write(relativePath, content, StandardCharsets.UTF_8);
    }

    static void write(String relativePath, String content, Charset charset) throws IOException {
        Path path = out.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, charset);
    }

    // ---------------------------------------------------------------- helpers

    void add(Account account, LocalDate date, String description, BigDecimal amount, String category) {
        addTxn(account, date, date, description, null, amount, category, false, "");
    }

    void addTransfer(Account account, LocalDate date, String description, BigDecimal amount) {
        addTxn(account, date, date, description, null, amount, "Transfer", true, "");
    }

    /** Apex purchase: positive {@code amount} as charged; posts 0-2 days after the transaction. */
    void apex(LocalDate date, String description, String bankCategory, BigDecimal amount, String category,
              String anomaly) {
        addTxn(Account.APEX, date, date.plusDays(rng.nextInt(3)), description, bankCategory, amount.negate(),
                category, false, anomaly);
    }

    /** Summit purchase: positive {@code amount} as charged. */
    void summit(LocalDate date, String description, BigDecimal amount, String category) {
        addTxn(Account.SUMMIT, date, date, description, null, amount.negate(), category, false, "");
    }

    void addTxn(Account account, LocalDate date, LocalDate postDate, String description, String bankCategory,
                BigDecimal amount, String category, boolean transfer, String anomaly) {
        require(!date.isBefore(START) && !date.isAfter(END), "date out of range: " + date);
        require(!description.contains(",") && !description.contains("\""), "unsafe description: " + description);
        txns.add(new Txn(account, date, postDate, description, bankCategory, amount, category, transfer, anomaly,
                seq++));
    }

    LocalDate randomDay(YearMonth month) {
        return month.atDay(1 + rng.nextInt(month.lengthOfMonth()));
    }

    /** A random amount between {@code minCents} and {@code maxCents}, inclusive. */
    BigDecimal cents(int minCents, int maxCents) {
        return BigDecimal.valueOf(minCents + rng.nextInt(maxCents - minCents + 1), 2);
    }

    String amazonReference() {
        String alphabet = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder ref = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            ref.append(alphabet.charAt(rng.nextInt(alphabet.length())));
        }
        return ref.toString();
    }

    static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    static BigDecimal sum(BigDecimal start, List<Txn> rows) {
        return rows.stream().map(Txn::amount).reduce(start, BigDecimal::add);
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
