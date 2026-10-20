package com.finalysis.analytics;

import com.finalysis.support.ImportTestSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ANA-06's independent oracle (docs/plans/phase-5.md §9.3): the expected month × category × account totals of the
 * sample data, computed from {@code answer-key.csv} with plain Java streams. It shares no code with analytics: no
 * SQL, no {@link TxnBucketSql}, no {@link BucketOracle}, no service. The counting rules are written out again here,
 * as the plan states them, for the cases the answer key has:
 * <ul>
 *   <li>{@code is_transfer = true}: a linked transfer, whatever its category;</li>
 *   <li>a Transfer category without {@code is_transfer}: an unlinked transfer;</li>
 *   <li>an income category (its kind is written here, from the seeded list): income, whatever the sign;</li>
 *   <li>Refunds &amp; Rebates: a refund, in spending;</li>
 *   <li>any other category: an expense, whatever the sign.</li>
 * </ul>
 * The month is the calendar month of the key's {@code date}, which is the txn date (the CSV layouts print one date).
 *
 * <p>Public for Phase 6's {@code ChatQuestionSetsTest}, whose month figures come from here.
 */
public final class AnswerKeyOracle {

    /** The seeded income categories (V2). Written here, not read from the database, so the oracle stays independent. */
    static final Set<String> INCOME = Set.of("Income", "Interest", "Person-to-Person In");
    static final String REFUNDS = "Refunds & Rebates";
    static final String TRANSFER = "Transfer";
    public static final String ALL = "ALL";

    /** One answer-key row. */
    record KeyRow(String file, String account, LocalDate date, String description, BigDecimal amount,
                  String category, boolean transfer, String anomaly) {

        YearMonth month() {
            return YearMonth.from(date);
        }

        /** The bucket by the plan's rules, as a name, so no app type decides it. */
        String bucket() {
            if (transfer) {
                return "LINKED_TRANSFER";
            }
            if (category.equals(TRANSFER)) {
                return "UNLINKED_TRANSFER";
            }
            if (category.equals(REFUNDS)) {
                return "REFUND";
            }
            return INCOME.contains(category) ? "INCOME" : "EXPENSE";
        }

        /** The row's category in the breakdown: none for a linked transfer (one row, whatever the category). */
        String rowCategory() {
            return transfer ? null : category;
        }
    }

    /** A cell: scope ({@link #ALL} or an account slug), month, bucket and category (null for the linked row). */
    record Key(String scope, YearMonth month, String bucket, String category) implements Comparable<Key> {

        @Override
        public int compareTo(Key other) {
            return toString().compareTo(other.toString());
        }
    }

    /** Signed sums and the row count, as the endpoints report them. */
    record Sums(BigDecimal moneyOut, BigDecimal moneyIn, int count) {

        static final Sums ZERO = new Sums(new BigDecimal("0.00"), new BigDecimal("0.00"), 0);

        static Sums of(List<KeyRow> rows) {
            BigDecimal out = new BigDecimal("0.00");
            BigDecimal in = new BigDecimal("0.00");
            for (KeyRow row : rows) {
                if (row.amount().signum() < 0) {
                    out = out.add(row.amount());
                } else {
                    in = in.add(row.amount());
                }
            }
            return new Sums(out, in, rows.size());
        }

        BigDecimal net() {
            return moneyOut.add(moneyIn);
        }
    }

    /**
     * A month's totals as §1.1 defines them, for one scope. The answer key has no uncategorized rows and no refunds,
     * so income is the income rows and spending the expense rows.
     */
    public record Month(BigDecimal income, BigDecimal spending, BigDecimal net, BigDecimal savingsRatePercent,
                 BigDecimal unlinked, BigDecimal linked, BigDecimal netChange, int txnCount) {
    }

    private final List<KeyRow> rows;

    private AnswerKeyOracle(List<KeyRow> rows) {
        this.rows = rows;
    }

    /** The whole answer key. */
    public static AnswerKeyOracle answerKey() {
        return answerKey(row -> true);
    }

    /** The answer key's rows that {@code keep} accepts: the broken-file variant drops the row its file lacks. */
    static AnswerKeyOracle answerKey(Predicate<KeyRow> keep) {
        try {
            List<KeyRow> rows = Files.readAllLines(ImportTestSupport.SAMPLE_DATA.resolve("answer-key.csv")).stream()
                    .skip(1)
                    .filter(line -> !line.isBlank())
                    .map(line -> line.split(",", -1))
                    .map(c -> new KeyRow(c[0], c[1], LocalDate.parse(c[2]), c[3], new BigDecimal(c[4]), c[5],
                            Boolean.parseBoolean(c[6]), c[7]))
                    .filter(keep)
                    .toList();
            return new AnswerKeyOracle(rows);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<KeyRow> rows() {
        return rows;
    }

    List<YearMonth> months() {
        return rows.stream().map(KeyRow::month).distinct().sorted().toList();
    }

    List<String> accounts() {
        return rows.stream().map(KeyRow::account).distinct().sorted().toList();
    }

    /** Every non-empty cell, for the household and each account. */
    Map<Key, Sums> cells() {
        Map<Key, Sums> cells = new TreeMap<>();
        for (String scope : scopes()) {
            rows.stream()
                    .filter(row -> scope.equals(ALL) || row.account().equals(scope))
                    .collect(Collectors.groupingBy(row -> new Key(scope, row.month(), row.bucket(), row.rowCategory())))
                    .forEach((key, group) -> cells.put(key, Sums.of(group)));
        }
        return cells;
    }

    /** A month's totals for a scope. */
    public Month month(String scope, YearMonth month) {
        List<KeyRow> inMonth = rows.stream()
                .filter(row -> scope.equals(ALL) || row.account().equals(scope))
                .filter(row -> row.month().equals(month))
                .toList();
        BigDecimal income = sum(inMonth, row -> row.bucket().equals("INCOME"));
        BigDecimal spending = sum(inMonth, row -> row.bucket().equals("EXPENSE") || row.bucket().equals("REFUND"));
        BigDecimal net = income.add(spending);
        BigDecimal rate = income.signum() > 0
                ? net.multiply(BigDecimal.valueOf(100)).divide(income, 1, RoundingMode.HALF_EVEN) : null;
        return new Month(income, spending, net, rate, sum(inMonth, row -> row.bucket().equals("UNLINKED_TRANSFER")),
                sum(inMonth, row -> row.bucket().equals("LINKED_TRANSFER")), sum(inMonth, row -> true), inMonth.size());
    }

    /** The household and each account. */
    List<String> scopes() {
        return Stream.concat(Stream.of(ALL), accounts().stream()).toList();
    }

    private static BigDecimal sum(List<KeyRow> rows, Predicate<KeyRow> which) {
        return rows.stream().filter(which).map(KeyRow::amount).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }
}
