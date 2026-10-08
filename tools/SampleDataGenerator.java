// Generates fully synthetic bank and credit card statements for tests and demos.
//
// Run from the repo root:  java tools/SampleDataGenerator.java
//
// Output is byte-identical on every run: fixed seed, fixed date range, no timestamps,
// locale-independent formatting. All institutions, account numbers, and the employer
// are fictional; merchant strings imitate real-world card descriptors.

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;

public class SampleDataGenerator {

    static final LocalDate START = LocalDate.of(2026, 4, 1);
    static final LocalDate END = LocalDate.of(2026, 9, 30);
    static final Path OUT = Path.of("sample-data");
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

        System.out.printf(Locale.ROOT, "Wrote %d statements, 1 broken statement, %d transactions to %s/%n",
                statements.size(), txns.size(), OUT);
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

    // ---------------------------------------------------------------- output

    static void clearOutput() throws IOException {
        for (String dir : List.of("statements", "broken")) {
            Path path = OUT.resolve(dir);
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.list(path)) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".csv")).toList()) {
                        Files.delete(file);
                    }
                }
            }
        }
    }

    static void write(String relativePath, String content) throws IOException {
        Path path = OUT.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
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
