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
        writeMerchantEdgeCases();
        writeCategorizationSets();

        System.out.printf(Locale.ROOT, "Wrote %d statements, 1 broken statement, %d OFX/QFX statements, %d edge cases, "
                + "%d transfer edge-case statements, 1 merchant edge-case statement, categorization dev and holdout sets, "
                + "%d transactions to %s/%n",
                statements.size(), ofxFiles, OFX_EDGE_CASES + CSV_EDGE_CASES, TRANSFER_EDGE_CASE_STATEMENTS,
                txns.size(), out);
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

    // ---------------------------------------------------------------- merchant cleaning edge cases

    static final String MERCHANTS_DIR = "edge-cases/merchants/";

    /** One row of the merchant fixture, with the merchant and counterparty cleaning should give. */
    record MerchantEdgeRow(int mayDay, String description, String amount, String merchant, String counterparty) {}

    /**
     * A May 2026 Northfield checking statement whose descriptions exercise merchant cleaning
     * (docs/plans/phase-4.md §2.3): person-to-person payments with names, ACH identifiers, processor
     * and channel prefixes, a store number with a location, a wrapped description (a quoted cell with
     * a line break), accents, a formula-leading cell, a description with nothing left after cleaning,
     * and one longer than 64 characters. Every name, number and business is fictional.
     *
     * <p>{@code merchants-expected.csv} lists each row's description (quoted like the statement's cell,
     * line break included), amount, and the expected merchant and counterparty (empty = none), written
     * here by hand rather than computed, so it is an oracle independent of the cleaning code. Written
     * last and without the random generator, so the other files are unchanged.
     */
    void writeMerchantEdgeCases() throws IOException {
        List<MerchantEdgeRow> rows = List.of(
                new MerchantEdgeRow(1, "HARBORLINE FREIGHT PAYROLL PPD ID: 7700112299", "2310.55", "HARBORLINE FREIGHT PAYROLL", ""),
                new MerchantEdgeRow(2, "ZELLE PAYMENT TO JANE Q SAMPLE CONF# 8XK2P1", "-60.00", "ZELLE", "JANE Q SAMPLE"),
                new MerchantEdgeRow(3, "ZELLE PAYMENT FROM ALEX R EXAMPLE CONF# 3MZ8Q0", "45.00", "ZELLE", "ALEX R EXAMPLE"),
                new MerchantEdgeRow(4, "ACH DEBIT ACME INS CO ID:9900112233 INDN:SAMPLE JANE", "-128.40", "ACH DEBIT ACME INS", ""),
                new MerchantEdgeRow(5, "CHECKCARD 0504 NORTHGATE FUEL #88 SAMPLETOWN VA", "-48.19", "NORTHGATE FUEL", ""),
                new MerchantEdgeRow(6, "POS DEBIT SQ *CORNER BEAN CAFE 0412", "-6.75", "CORNER BEAN CAFE", ""),
                new MerchantEdgeRow(7, "MAPLEWOOD GROCERY\n#214 SAMPLETOWN VA", "-86.42", "MAPLEWOOD GROCERY", ""),
                new MerchantEdgeRow(8, "ONLINE TRANSFER TO SAVINGS XXXXXX1002", "-500.00", "ONLINE TRANSFER TO SAVINGS", ""),
                new MerchantEdgeRow(9, "CAFÉ LUMIÈRE", "-12.30", "CAFE LUMIERE", ""),
                new MerchantEdgeRow(11, "=HYPERLINK(\"x\")", "-1.00", "HYPERLINK X", ""),
                new MerchantEdgeRow(12, "#12345 0099", "-3.00", "", ""),
                new MerchantEdgeRow(14, "VENMO PAYMENT 1023456789 WEB ID: 3264681992", "-25.00", "VENMO", ""),
                new MerchantEdgeRow(15, "CASH APP*JANE SAMPLE", "-15.00", "CASH APP", "JANE SAMPLE"),
                new MerchantEdgeRow(18, "SUNRISE COMMUNITY HEALTH AND WELLNESS CENTER OUTPATIENT PHYSICAL THERAPY SERVICES",
                        "-40.00", "SUNRISE COMMUNITY HEALTH AND WELLNESS CENTER OUTPATIENT PHYSICAL", ""));

        BigDecimal balance = money("5000.00");
        List<String> statement = new ArrayList<>(List.of("Date,Description,Debit,Credit,Balance"));
        List<String> expected = new ArrayList<>(List.of("description,amount,merchant,counterparty"));
        for (MerchantEdgeRow r : rows) {
            BigDecimal amount = money(r.amount());
            balance = balance.add(amount);
            String debit = amount.signum() < 0 ? format(amount.negate()) : "";
            String credit = amount.signum() > 0 ? format(amount) : "";
            statement.add(csvRow(northfieldDate(LocalDate.of(2026, 5, r.mayDay())), r.description(), debit, credit,
                    format(balance)));
            require(r.merchant().length() <= 64, "merchant edge case: expected merchant longer than 64: " + r.merchant());
            expected.add(csvRow(r.description(), r.amount(), r.merchant(), r.counterparty()));
        }
        write(MERCHANTS_DIR + "merchants-northfield-checking_2026-05.csv", String.join("\n", statement) + "\n");
        write(MERCHANTS_DIR + "merchants-expected.csv", String.join("\n", expected) + "\n");
    }

    // ---------------------------------------------------------------- categorization dev and holdout sets

    static final String CATEGORIZATION_DIR = "categorization/";
    static final String CATEGORIZATION_HEADER = "description,amount,expected_category,name";

    /** How a business writes its descriptions; each channel has its own formats. */
    enum Channel { CARD, DINING, ONLINE, BILL, PAYROLL }

    /**
     * A fictional business in one set's name pool. {@code name} is the distinctive part, which never
     * shares a word with the other set's pool; {@code kind} is a generic word such as DELI or DENTAL,
     * or empty. {@code cents} is a typical amount, always positive; the sign comes from the channel.
     */
    record Business(String name, String kind, String category, Channel channel, int cents, int rows) {
        String merchant() {
            return kind.isEmpty() ? name : name + " " + kind;
        }
    }

    /** A refund (money in) from one of the set's own businesses. */
    record Refund(String name, int cents) {}

    /** A line with a fixed description: transfers, interest, fees, cash, person-to-person, rewards. */
    record GenericRow(String description, String amount, String category, String name) {}

    record CategorizationSet(String file, List<String> comments, long seed, String cardLastFour,
                             List<String> places, List<Business> businesses, List<Refund> refunds,
                             List<GenericRow> generic) {}

    static final List<String> CARD_FORMATS = List.of(
            "{M} {N4} {PLACE}",
            "CHECKCARD {MMDD} {M} {PLACE}",
            "{M} #{N3} {PLACE}",
            "POS DEBIT {M} {PLACE}",
            "{M}",
            "PURCHASE AUTHORIZED ON {MM/DD} {M} {PLACE} S{N15} CARD {LAST4}",
            "{TITLE}",
            "{M} {PLACE}",
            "DEBIT CARD PURCHASE {M} {N4}");
    static final List<String> DINING_FORMATS = List.of(
            "SQ *{M} {N4}",
            "TST* {M}",
            "{M} {N4} {PLACE}",
            "SQ *{M} {PLACE}",
            "CHECKCARD {MMDD} {M} {PLACE}",
            "{TITLE}",
            "TST*{M} {N3}");
    static final List<String> ONLINE_FORMATS = List.of(
            "{M}.COM",
            "{NOSPACE}*{REF} {PHONE} {ST}",
            "PAYPAL *{NOSPACE}",
            "{M} {PHONE} {ST}",
            "RECURRING PMT {M}",
            "{TITLE}");
    static final List<String> BILL_FORMATS = List.of(
            "{M} WEB PMT",
            "ACH DEBIT {M} CO ID:{N10} INDN:{PERSON}",
            "{M} BILL PAY {MMDD}",
            "{M} ONLINE PMT CKF{N9}POS",
            "{M} AUTOPAY");
    static final List<String> PAYROLL_FORMATS = List.of(
            "{M} PAYROLL PPD ID: {N10}",
            "DIRECT DEP {M} PPD ID: {N10}",
            "{M} DIR DEP {MMDD}");
    static final List<String> REFUND_FORMATS = List.of(
            "RETURN {M} {N4} {PLACE}",
            "{M} REFUND",
            "CREDIT VOUCHER {M} {PLACE}",
            "{M} {N4} {PLACE}");
    static final List<String> ONLINE_STATES = List.of("CA", "WA", "NY", "TX");

    /**
     * Two synthetic sets of {@code description,amount,expected_category,name} rows for CAT-05
     * (docs/plans/phase-4.md §10, step 1b): {@code dev.csv} tunes seed rules, cleaning patterns and
     * prompts; {@code holdout.csv} is frozen and only reported on. Every business, person, number and
     * card issuer is fictional; places are generic town names. {@code name} is the row's distinctive
     * merchant name (empty for generic lines), and the two sets draw it from disjoint pools, so a rule
     * written for a dev merchant can't match a holdout merchant by name. Written last, with a
     * per-set counter instead of the random generator, so every other file is unchanged.
     */
    void writeCategorizationSets() throws IOException {
        writeCategorizationSet(devSet());
        writeCategorizationSet(holdoutSet());
    }

    static void writeCategorizationSet(CategorizationSet set) throws IOException {
        SetWriter w = new SetWriter(set);
        List<Business> businesses = set.businesses();
        for (int b = 0; b < businesses.size(); b++) {
            Business business = businesses.get(b);
            List<String> formats = switch (business.channel()) {
                case CARD -> CARD_FORMATS;
                case DINING -> DINING_FORMATS;
                case ONLINE -> ONLINE_FORMATS;
                case BILL -> BILL_FORMATS;
                case PAYROLL -> PAYROLL_FORMATS;
            };
            boolean varies = business.channel() == Channel.CARD || business.channel() == Channel.DINING
                    || business.category().equals("Utilities");
            for (int k = 0; k < business.rows(); k++) {
                int cents = varies ? business.cents() * (80 + (k * 37 + b * 11) % 41) / 100 : business.cents();
                BigDecimal amount = BigDecimal.valueOf(cents, 2);
                w.add(w.describe(formats.get((b + k) % formats.size()), business.merchant()),
                        business.channel() == Channel.PAYROLL ? amount : amount.negate(),
                        business.category(), business.name());
            }
        }
        for (int r = 0; r < set.refunds().size(); r++) {
            Refund refund = set.refunds().get(r);
            Business business = businesses.stream().filter(x -> x.name().equals(refund.name())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("refund for unknown business " + refund.name()));
            w.add(w.describe(REFUND_FORMATS.get(r % REFUND_FORMATS.size()), business.merchant()),
                    BigDecimal.valueOf(refund.cents(), 2), "Refunds & Rebates", business.name());
        }
        for (GenericRow g : set.generic()) {
            w.add(g.description(), money(g.amount()), g.category(), g.name());
        }
        write(CATEGORIZATION_DIR + set.file(), w.content());
    }

    /** Builds one set's rows; numbers, dates and places come from a counter, not from {@code rng}. */
    static final class SetWriter {
        private final CategorizationSet set;
        private final List<String> lines = new ArrayList<>();
        private long state;
        private int serial;

        SetWriter(CategorizationSet set) {
            this.set = set;
            this.state = set.seed();
            set.comments().forEach(c -> lines.add("# " + c));
            lines.add(CATEGORIZATION_HEADER);
        }

        void add(String description, BigDecimal amount, String category, String name) {
            require(!description.isEmpty() && !description.contains(",") && !description.contains("\"")
                    && !description.startsWith("#"), "unsafe categorization description: " + description);
            require(amount.signum() != 0, "zero amount: " + description);
            require(name.isEmpty() || description.toUpperCase(Locale.ROOT).replace(" ", "")
                    .contains(name.replace(" ", "")), "name not in description: " + description);
            lines.add(csvRow(description, format(amount), category, name));
            serial++;
        }

        String describe(String format, String merchant) {
            String place = set.places().get(serial % set.places().size());
            String city = place.substring(0, place.lastIndexOf(' '));
            int month = 4 + serial % 6;
            int day = 1 + (serial * 7) % 28;
            String text = format
                    .replace("{M}", merchant)
                    .replace("{TITLE}", titleCase(merchant + " " + city))
                    .replace("{NOSPACE}", merchant.replace(" ", ""))
                    .replace("{PLACE}", place)
                    .replace("{MMDD}", String.format(Locale.ROOT, "%02d%02d", month, day))
                    .replace("{MM/DD}", String.format(Locale.ROOT, "%02d/%02d", month, day))
                    .replace("{LAST4}", set.cardLastFour())
                    .replace("{ST}", ONLINE_STATES.get(serial % ONLINE_STATES.size()))
                    .replace("{PERSON}", serial % 2 == 0 ? "SAMPLE JANE" : "EXAMPLE ALEX")
                    .replace("{PHONE}", "888-555-01" + digits(2))
                    .replace("{REF}", reference())
                    .replace("{N15}", digits(15))
                    .replace("{N10}", digits(10))
                    .replace("{N9}", digits(9))
                    .replace("{N4}", digits(4))
                    .replace("{N3}", digits(3));
            require(!text.contains("{"), "unfilled placeholder: " + text);
            return text;
        }

        String content() {
            return String.join("\n", lines) + "\n";
        }

        /** A 64-bit linear congruential step (Knuth's MMIX constants): fixed output, no {@code rng}. */
        private int next(int bound) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            return (int) ((state >>> 33) % bound);
        }

        private String digits(int count) {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < count; i++) {
                s.append((char) ('0' + next(10)));
            }
            return s.toString();
        }

        private String reference() {
            String alphabet = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                s.append(alphabet.charAt(next(alphabet.length())));
            }
            return s.toString();
        }

        private static String titleCase(String text) {
            StringBuilder s = new StringBuilder();
            for (String word : text.split(" ")) {
                if (!s.isEmpty()) {
                    s.append(' ');
                }
                s.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
            }
            return s.toString();
        }
    }

    static CategorizationSet devSet() {
        return new CategorizationSet("dev.csv",
                List.of("Finalysis categorization DEV set (CAT-05, docs/plans/phase-4.md section 10).",
                        "Tune seed rules, cleaning patterns and prompts against this file and the answer key only.",
                        "Synthetic: every business, person, number and card issuer is fictional.",
                        "Distinctive names (column name) never share a word with holdout.csv."),
                20261014L, "9921",
                List.of("OAKDALE VA", "RIVERTON MD", "GLENWOOD VA", "LAKEWOOD CO", "SPRING HILL TN", "FAIRMONT WV"),
                List.of(
                        new Business("MAPLE ST", "DELI", "Dining", Channel.DINING, 1450, 3),
                        new Business("COPPERLEAF", "CAFE", "Dining", Channel.DINING, 680, 4),
                        new Business("LITTLE ORCHID", "THAI KITCHEN", "Dining", Channel.DINING, 2600, 3),
                        new Business("BRASSWORKS", "TAVERN", "Dining", Channel.DINING, 3800, 2),
                        new Business("NONNA ROSAS", "PIZZERIA", "Dining", Channel.DINING, 2200, 3),
                        new Business("QUILLBY", "BAGELS", "Dining", Channel.DINING, 940, 2),
                        new Business("EMBERSIDE", "GRILL", "Dining", Channel.DINING, 3100, 2),
                        new Business("SAFFRON ROW", "", "Dining", Channel.DINING, 2750, 2),
                        new Business("HEARTHSTONE", "BAKERY", "Dining", Channel.DINING, 875, 3),
                        new Business("GREENVALE", "MARKET", "Groceries", Channel.CARD, 7200, 4),
                        new Business("FARMSTEAD", "FOODS", "Groceries", Channel.CARD, 9400, 3),
                        new Business("HOLLIS", "FRESH MART", "Groceries", Channel.CARD, 5600, 3),
                        new Business("PEPPERTREE", "GROCERY", "Groceries", Channel.CARD, 4300, 2),
                        new Business("TALLGRASS", "ORGANICS", "Groceries", Channel.CARD, 6100, 2),
                        new Business("HARBOR LIGHT", "DENTAL", "Health", Channel.CARD, 18500, 2),
                        new Business("WILLOWBROOK", "PHARMACY", "Health", Channel.CARD, 1890, 3),
                        new Business("CEDARPOINT", "FAMILY MEDICINE", "Health", Channel.CARD, 4000, 2),
                        new Business("BRIGHTPATH", "OPTOMETRY", "Health", Channel.CARD, 12500, 1),
                        new Business("LUMEN", "PHYSICAL THERAPY", "Health", Channel.CARD, 4500, 2),
                        new Business("THREADLINE", "OUTFITTERS", "Shopping", Channel.CARD, 6400, 3),
                        new Business("PAPER LANTERN", "BOOKS", "Shopping", Channel.CARD, 2350, 3),
                        new Business("IRONGATE", "HARDWARE", "Shopping", Channel.CARD, 3450, 3),
                        new Business("KESTREL", "HOME GOODS", "Shopping", Channel.CARD, 8800, 2),
                        new Business("VOLTARA", "ELECTRONICS", "Shopping", Channel.CARD, 15900, 2),
                        new Business("MARIGOLD & FERN", "", "Shopping", Channel.CARD, 4200, 2),
                        new Business("PINWHEEL", "TOYS", "Shopping", Channel.CARD, 2999, 2),
                        new Business("STREAMVALE", "", "Subscriptions", Channel.ONLINE, 1599, 3),
                        new Business("TUNEHARBOR", "", "Subscriptions", Channel.ONLINE, 1099, 3),
                        new Business("CLOUDNEST", "STORAGE", "Subscriptions", Channel.ONLINE, 299, 2),
                        new Business("PIXELFORGE", "GAMES", "Subscriptions", Channel.ONLINE, 999, 2),
                        new Business("DAILY QUILL", "NEWS", "Subscriptions", Channel.ONLINE, 1500, 2),
                        new Business("FITLOOP", "", "Subscriptions", Channel.ONLINE, 1299, 2),
                        new Business("RIVERBEND", "FUEL", "Transportation", Channel.CARD, 4800, 4),
                        new Business("SWIFTLANE", "RIDES", "Transportation", Channel.CARD, 1850, 3),
                        new Business("CITYPARK", "PARKING", "Transportation", Channel.CARD, 1200, 2),
                        new Business("GEARWORKS", "AUTO REPAIR", "Transportation", Channel.CARD, 38500, 1),
                        new Business("RAILSTAR", "TRANSIT", "Transportation", Channel.CARD, 275, 2),
                        new Business("NORTHRIDGE", "ELECTRIC", "Utilities", Channel.BILL, 11240, 3),
                        new Business("CLEARSPRING", "WATER UTIL", "Utilities", Channel.BILL, 4630, 2),
                        new Business("WAVECREST", "WIRELESS", "Utilities", Channel.BILL, 6500, 3),
                        new Business("FIBERLINK", "INTERNET", "Utilities", Channel.BILL, 7999, 3),
                        new Business("ASHFORD", "PROPERTY MGMT RENT", "Housing", Channel.BILL, 185000, 3),
                        new Business("STONEBRIDGE", "MORTGAGE", "Mortgage", Channel.BILL, 214500, 2),
                        new Business("LAKEHAVEN", "HOA DUES", "HOA & Condo Fees", Channel.BILL, 32500, 2),
                        new Business("BAYSHORE", "TOLL AUTHORITY", "Tolls", Channel.CARD, 650, 3),
                        new Business("NORTHWIND", "ANALYTICS", "Income", Channel.PAYROLL, 312455, 3)),
                List.of(new Refund("THREADLINE", 6400), new Refund("PAPER LANTERN", 1299),
                        new Refund("VOLTARA", 15900), new Refund("GREENVALE", 845)),
                List.of(
                        new GenericRow("ONLINE TRANSFER TO SAVINGS XXXXXX4417", "-400.00", "Transfer", ""),
                        new GenericRow("ONLINE TRANSFER TO SAVINGS XXXXXX4417", "-250.00", "Transfer", ""),
                        new GenericRow("ONLINE TRANSFER FROM SAVINGS XXXXXX4417", "300.00", "Transfer", ""),
                        new GenericRow("ONLINE TRANSFER FROM CHECKING XXXXXX3308", "400.00", "Transfer", ""),
                        new GenericRow("QUILLMARK VISA AUTOPAY", "-612.38", "Transfer", "QUILLMARK"),
                        new GenericRow("QUILLMARK VISA AUTOPAY", "-488.10", "Transfer", "QUILLMARK"),
                        new GenericRow("PAYMENT THANK YOU", "612.38", "Transfer", ""),
                        new GenericRow("AUTOMATIC PAYMENT - THANK YOU", "488.10", "Transfer", ""),
                        new GenericRow("TRANSFER TO CHK XXXXXX3308 CONFIRMATION# 4471029", "-150.00", "Transfer", ""),
                        new GenericRow("INTEREST PAYMENT", "4.12", "Interest", ""),
                        new GenericRow("INTEREST EARNED", "3.87", "Interest", ""),
                        new GenericRow("INTEREST PAYMENT", "4.31", "Interest", ""),
                        new GenericRow("MONTHLY SERVICE FEE", "-12.00", "Bank Fees", ""),
                        new GenericRow("OVERDRAFT FEE FOR ITEM 0412", "-35.00", "Bank Fees", ""),
                        new GenericRow("FOREIGN TRANSACTION FEE", "-1.87", "Bank Fees", ""),
                        new GenericRow("OUT OF NETWORK ATM FEE", "-3.00", "Bank Fees", ""),
                        new GenericRow("ATM WITHDRAWAL #004512 OAKDALE VA", "-100.00", "Cash & ATM", ""),
                        new GenericRow("ATM WITHDRAWAL #004512 OAKDALE VA", "-60.00", "Cash & ATM", ""),
                        new GenericRow("NON-BANK ATM WITHDRAWAL RIVERTON MD", "-40.00", "Cash & ATM", ""),
                        new GenericRow("CASH WITHDRAWAL BRANCH 0217", "-200.00", "Cash & ATM", ""),
                        new GenericRow("ZELLE PAYMENT TO JANE Q SAMPLE CONF# 7RT2K9", "-75.00", "Person-to-Person Out", ""),
                        new GenericRow("ZELLE PAYMENT TO RILEY DEMO CONF# 2PL8X4", "-40.00", "Person-to-Person Out", ""),
                        new GenericRow("ZELLE PAYMENT FROM ALEX R EXAMPLE CONF# 9QW3M1", "120.00", "Person-to-Person In", ""),
                        new GenericRow("VENMO PAYMENT 1029384756 WEB ID: 3264681992", "-22.50", "Person-to-Person Out", ""),
                        new GenericRow("VENMO CASHOUT PPD ID: 5264681992", "85.00", "Person-to-Person In", ""),
                        new GenericRow("CASH APP*SAM PLACEHOLDER", "-18.00", "Person-to-Person Out", ""),
                        new GenericRow("CASH APP*CASH OUT", "35.00", "Person-to-Person In", ""),
                        new GenericRow("CASHBACK REWARD REDEMPTION", "25.00", "Refunds & Rebates", ""),
                        new GenericRow("STATEMENT CREDIT REWARDS", "15.00", "Refunds & Rebates", "")));
    }

    static CategorizationSet holdoutSet() {
        return new CategorizationSet("holdout.csv",
                List.of("Finalysis categorization HOLDOUT set (CAT-05, docs/plans/phase-4.md section 10).",
                        "FROZEN: reporting only. Never tune seed rules, cleaning patterns or prompts against it; use dev.csv.",
                        "Its SHA-256 is pinned by CategorizationSetsTest and recorded in the plan; any edit fails the build.",
                        "The same author wrote these rows and the seed rules: procedural protection, not a blind evaluation.",
                        "Synthetic: every business, person, number and card issuer is fictional.",
                        "Distinctive names (column name) never share a word with dev.csv."),
                20261015L, "6630",
                List.of("BRIARCLIFF NY", "MILLBROOK NY", "CEDAR GROVE NJ", "WESTFIELD IN", "PORTSMOUTH NH", "ELM CREEK OR"),
                List.of(
                        new Business("OSPREY", "NOODLE BAR", "Dining", Channel.DINING, 1650, 3),
                        new Business("JUNIPER HOLLOW", "BISTRO", "Dining", Channel.DINING, 4200, 2),
                        new Business("TWO RIVERS", "TAQUERIA", "Dining", Channel.DINING, 1350, 3),
                        new Business("LANTERNFISH", "SUSHI", "Dining", Channel.DINING, 3600, 2),
                        new Business("HONEYCOMB", "BAKERY", "Dining", Channel.DINING, 720, 3),
                        new Business("GRANITE PEAK", "BREWING", "Dining", Channel.DINING, 2900, 2),
                        new Business("CORVINA", "", "Dining", Channel.DINING, 5400, 1),
                        new Business("FIDDLEHEAD", "KITCHEN", "Dining", Channel.DINING, 2450, 2),
                        new Business("WOODSMOKE", "BBQ", "Dining", Channel.DINING, 2100, 2),
                        new Business("POPPYSEED", "CAFE", "Dining", Channel.DINING, 610, 3),
                        new Business("MOONGATE", "DUMPLING HOUSE", "Dining", Channel.DINING, 1950, 2),
                        new Business("SUNFIELD", "GROCERS", "Groceries", Channel.CARD, 8100, 3),
                        new Business("ORCHARD LANE", "MARKET", "Groceries", Channel.CARD, 6400, 3),
                        new Business("BRAMBLE", "FOOD CO-OP", "Groceries", Channel.CARD, 5200, 2),
                        new Business("HIGHVIEW", "SUPERMARKET", "Groceries", Channel.CARD, 9800, 3),
                        new Business("SALTMARSH", "FISH MARKET", "Groceries", Channel.CARD, 3400, 1),
                        new Business("GOLDENROD", "PRODUCE", "Groceries", Channel.CARD, 2800, 2),
                        new Business("PINECREST", "DENTAL CARE", "Health", Channel.CARD, 21000, 1),
                        new Business("ALDERWOOD", "CHIROPRACTIC", "Health", Channel.CARD, 6500, 2),
                        new Business("RIVERSTONE", "URGENT CARE", "Health", Channel.CARD, 7500, 1),
                        new Business("MEADOWLARK", "PHARMACY", "Health", Channel.CARD, 2240, 3),
                        new Business("CLEARVIEW", "EYE CARE", "Health", Channel.CARD, 9900, 1),
                        new Business("SOLACE", "COUNSELING", "Health", Channel.CARD, 12000, 1),
                        new Business("BIRCHWELL", "PEDIATRICS", "Health", Channel.CARD, 3500, 1),
                        new Business("SILVERBIRCH", "BOUTIQUE", "Shopping", Channel.CARD, 5800, 2),
                        new Business("FOXGLOVE", "GIFTS", "Shopping", Channel.CARD, 2650, 2),
                        new Business("TALLPINE", "SPORTING GOODS", "Shopping", Channel.CARD, 7400, 2),
                        new Business("CRANEWOOD", "FURNITURE", "Shopping", Channel.CARD, 42900, 1),
                        new Business("WRENFIELD", "BOOKS", "Shopping", Channel.CARD, 1899, 2),
                        new Business("OBSIDIAN & OAK", "", "Shopping", Channel.CARD, 3800, 2),
                        new Business("TINKERBOX", "HOBBY SHOP", "Shopping", Channel.CARD, 2275, 2),
                        new Business("GADGETRY", "", "Shopping", Channel.CARD, 12999, 1),
                        new Business("PATCHWORK", "THRIFT", "Shopping", Channel.CARD, 1600, 2),
                        new Business("VIDEOLARK", "", "Subscriptions", Channel.ONLINE, 1399, 3),
                        new Business("SONGBIRD", "PLUS", "Subscriptions", Channel.ONLINE, 1199, 3),
                        new Business("NIMBUSVAULT", "", "Subscriptions", Channel.ONLINE, 999, 2),
                        new Business("PUZZLEBOX", "MONTHLY", "Subscriptions", Channel.ONLINE, 1800, 2),
                        new Business("STRIDEPULSE", "APP", "Subscriptions", Channel.ONLINE, 1499, 2),
                        new Business("TRAILHEAD", "GAS", "Transportation", Channel.CARD, 5200, 4),
                        new Business("ZIPWAY", "RIDESHARE", "Transportation", Channel.CARD, 2150, 3),
                        new Business("DOCKSIDE", "PARKING GARAGE", "Transportation", Channel.CARD, 1500, 2),
                        new Business("CARRIAGE HOUSE", "AUTO SERVICE", "Transportation", Channel.CARD, 26400, 1),
                        new Business("TIDEPOOL", "FERRY", "Transportation", Channel.CARD, 950, 2),
                        new Business("SPOKEWISE", "BIKE SHARE", "Transportation", Channel.CARD, 350, 2),
                        new Business("HIGHLAND", "GAS & ELECTRIC", "Utilities", Channel.BILL, 13800, 3),
                        new Business("PRAIRIE", "WATER DEPT", "Utilities", Channel.BILL, 5100, 2),
                        new Business("SIGNALPOINT", "MOBILE", "Utilities", Channel.BILL, 7200, 3),
                        new Business("CABLEWAY", "BROADBAND", "Utilities", Channel.BILL, 8999, 3),
                        new Business("GREYSTONE", "WASTE SERVICES", "Utilities", Channel.BILL, 3800, 2),
                        new Business("WESTMERE", "APARTMENTS", "Housing", Channel.BILL, 172500, 3),
                        new Business("GRANDVIEW", "HOME LOANS", "Mortgage", Channel.BILL, 198700, 2),
                        new Business("FOXRUN", "CONDO ASSN", "HOA & Condo Fees", Channel.BILL, 41000, 2),
                        new Business("COASTAL", "EXPRESSWAY TOLL", "Tolls", Channel.CARD, 525, 3),
                        new Business("LARKHAVEN", "MEDICAL GROUP", "Income", Channel.PAYROLL, 287630, 3)),
                List.of(new Refund("SILVERBIRCH", 5800), new Refund("TALLPINE", 7400),
                        new Refund("SUNFIELD", 1250), new Refund("GADGETRY", 12999)),
                List.of(
                        new GenericRow("ONLINE BANKING TRANSFER TO SAV XXXXXX8823", "-350.00", "Transfer", ""),
                        new GenericRow("ONLINE BANKING TRANSFER TO SAV XXXXXX8823", "-200.00", "Transfer", ""),
                        new GenericRow("MOBILE TRANSFER FROM SAV XXXXXX8823", "500.00", "Transfer", ""),
                        new GenericRow("ONLINE TRANSFER FROM CHECKING XXXXXX5590", "350.00", "Transfer", ""),
                        new GenericRow("VESPERA CARD PAYMENT", "-733.20", "Transfer", "VESPERA"),
                        new GenericRow("VESPERA CARD PAYMENT", "-402.65", "Transfer", "VESPERA"),
                        new GenericRow("PAYMENT RECEIVED - THANK YOU", "733.20", "Transfer", ""),
                        new GenericRow("ONLINE PAYMENT THANK YOU", "402.65", "Transfer", ""),
                        new GenericRow("XFER TO CHECKING XXXXXX5590 REF #8814402", "-120.00", "Transfer", ""),
                        new GenericRow("INTEREST PAID", "5.02", "Interest", ""),
                        new GenericRow("INTEREST CREDIT", "4.66", "Interest", ""),
                        new GenericRow("DIVIDEND EARNED", "6.10", "Interest", ""),
                        new GenericRow("SERVICE FEE MONTHLY MAINTENANCE", "-15.00", "Bank Fees", ""),
                        new GenericRow("NSF RETURNED ITEM FEE", "-34.00", "Bank Fees", ""),
                        new GenericRow("WIRE TRANSFER FEE", "-25.00", "Bank Fees", ""),
                        new GenericRow("INTL TRANSACTION FEE", "-2.14", "Bank Fees", ""),
                        new GenericRow("ATM CASH WITHDRAWAL 7722 MILLBROOK NY", "-80.00", "Cash & ATM", ""),
                        new GenericRow("ATM CASH WITHDRAWAL 7722 MILLBROOK NY", "-40.00", "Cash & ATM", ""),
                        new GenericRow("WITHDRAWAL AT BRANCH CEDAR GROVE NJ", "-200.00", "Cash & ATM", ""),
                        new GenericRow("NON-NETWORK ATM CASH BRIARCLIFF NY", "-60.00", "Cash & ATM", ""),
                        new GenericRow("ZELLE PAYMENT TO PAT M TESTER CONF# 5HD9K2", "-50.00", "Person-to-Person Out", ""),
                        new GenericRow("ZELLE TO CHRIS L FICTION ON 06/14 REF # PP0RDK3X2T", "-90.00", "Person-to-Person Out", ""),
                        new GenericRow("ZELLE PAYMENT FROM DANA SPECIMEN CONF# 1ZX7B4", "65.00", "Person-to-Person In", ""),
                        new GenericRow("ZELLE FROM MORGAN K MOCK ON 08/02 REF # PP0QWT7Y1Z", "30.00", "Person-to-Person In", ""),
                        new GenericRow("VENMO PAYMENT 3049581726 WEB ID: 3264681993", "-27.00", "Person-to-Person Out", ""),
                        new GenericRow("VENMO CASHOUT PPD ID: 5264681993", "110.00", "Person-to-Person In", ""),
                        new GenericRow("CASH APP*PAT M TESTER", "-12.00", "Person-to-Person Out", ""),
                        new GenericRow("REWARDS CREDIT CASH BACK", "30.00", "Refunds & Rebates", ""),
                        new GenericRow("CASHBACK BONUS REDEMPTION", "20.00", "Refunds & Rebates", "")));
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
        for (String dir : List.of("statements", "broken", "ofx", "edge-cases", "edge-cases/transfers", "edge-cases/merchants",
                "categorization")) {
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
