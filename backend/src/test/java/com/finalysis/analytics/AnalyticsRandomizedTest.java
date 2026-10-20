package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.analytics.BucketOracle.Kind;
import com.finalysis.analytics.BucketOracle.Row;
import com.finalysis.analytics.BucketOracle.Totals;
import com.finalysis.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * docs/plans/phase-5.md §9.2's randomized check against the database: a seeded generator writes 2,000 txns over two
 * accounts and 24 months, with random categories (each kind, the spending-offset one, none), signs (zero included),
 * links, "not a transfer" marks and review reasons. Every month's totals, for the household and each account, must
 * equal {@link BucketOracle}'s for the same rows: plain Java that shares no code with the SQL. Rolled back afterwards.
 *
 * <p>The seed is fixed, so a failure replays; the message names it.
 */
@IntegrationTest
@Transactional
class AnalyticsRandomizedTest {

    static final long SEED = 20261020L;
    static final int TXNS = 2_000;
    static final MonthRange RANGE = new MonthRange(YearMonth.of(2025, 1), YearMonth.of(2026, 12));

    /** The category choices: seeded names, their kind as the oracle sees it, and the spending-offset flag. */
    private enum Choice {
        NONE(null, null, false),
        EXPENSE("Dining", Kind.EXPENSE, false),
        EXPENSE_2("Housing", Kind.EXPENSE, false),
        INCOME("Income", Kind.INCOME, false),
        TRANSFER("Transfer", Kind.TRANSFER, false),
        OFFSET("Refunds & Rebates", Kind.INCOME, true);

        final String name;
        final Kind kind;
        final boolean offset;

        Choice(String name, Kind kind, boolean offset) {
            this.name = name;
            this.kind = kind;
            this.offset = offset;
        }
    }

    /** One generated txn: what the oracle needs, plus the columns the totals also report. */
    private record Generated(long accountId, LocalDate date, Row row, boolean reviewPending) {
    }

    private final JdbcTemplate jdbc;
    private final MonthlyTotalsService months;

    AnalyticsRandomizedTest(JdbcTemplate jdbc, MonthlyTotalsService months) {
        this.jdbc = jdbc;
        this.months = months;
    }

    @Test
    void totalsEqualTheOracleOnRandomRows() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM statement", Integer.class))
                .as("household totals cover every account").isZero();
        List<Long> accounts = List.of(account("9911"), account("9912"));
        Map<Long, Long> statements = new HashMap<>();
        accounts.forEach(account -> statements.put(account, statement(account)));
        Map<String, Long> categoryIds = new HashMap<>();
        for (Choice choice : Choice.values()) {
            if (choice.name != null) {
                categoryIds.put(choice.name, jdbc.queryForObject("SELECT id FROM category WHERE name = ?",
                        Long.class, choice.name));
            }
        }

        Random random = new Random(SEED);
        List<Generated> generated = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        int days = (int) (RANGE.dayAfter().toEpochDay() - RANGE.firstDay().toEpochDay());
        for (int i = 0; i < TXNS; i++) {
            long account = accounts.get(random.nextInt(accounts.size()));
            LocalDate date = RANGE.firstDay().plusDays(random.nextInt(days));
            Choice choice = Choice.values()[random.nextInt(Choice.values().length)];
            // One in ten is zero; the rest −500.00 to +500.00, in cents.
            BigDecimal amount = random.nextInt(10) == 0 ? new BigDecimal("0.00")
                    : BigDecimal.valueOf(random.nextInt(100_001) - 50_000, 2);
            boolean linked = random.nextInt(5) == 0;
            boolean excluded = !linked && random.nextInt(6) == 0;
            String reviewReason = choice == Choice.NONE && random.nextInt(3) == 0 ? "LOW_CONFIDENCE" : null;
            Long categoryId = choice.name == null ? null : categoryIds.get(choice.name);

            Row row = new Row(linked, choice.kind, choice.offset, excluded, amount);
            boolean pending = !linked && choice == Choice.NONE && (reviewReason != null || excluded);
            generated.add(new Generated(account, date, row, pending));
            rows.add(new Object[] {account, statements.get(account), date, amount, categoryId,
                    categoryId == null ? null : "USER", excluded, reviewReason, reviewReason, "random-" + i});
        }
        jdbc.batchUpdate("""
                INSERT INTO txn (account_id, statement_id, txn_date, raw_description, amount, source_section,
                                 category_id, category_source, transfer_excluded, ai_review_reason, ai_review_at,
                                 fingerprint)
                VALUES (?, ?, ?, 'RANDOM CASE', ?, 'OTHER', ?, ?, ?, ?, CASE WHEN ?::text IS NULL THEN NULL ELSE now() END,
                        ?)
                """, rows);
        linkTheLinkedOnes(generated);

        List<String> mismatches = new ArrayList<>();
        List<Optional<Long>> scopes = List.of(Optional.empty(), Optional.of(accounts.get(0)),
                Optional.of(accounts.get(1)));
        for (Optional<Long> scope : scopes) {
            List<MonthTotals> actual = months.months(Optional.of(RANGE.from()), Optional.of(RANGE.to()), scope)
                    .months();
            assertThat(actual).hasSize(24);
            for (MonthTotals m : actual) {
                List<Generated> inMonth = generated.stream()
                        .filter(g -> scope.isEmpty() || g.accountId() == scope.get())
                        .filter(g -> YearMonth.from(g.date()).equals(m.month()))
                        .toList();
                compare(mismatches, scope.map(id -> "account " + id).orElse("household") + " " + m.month(), m,
                        inMonth);
            }
        }
        assertThat(mismatches).as("seed " + SEED).isEmpty();
    }

    private static void compare(List<String> mismatches, String where, MonthTotals actual, List<Generated> rows) {
        Totals expected = BucketOracle.totals(rows.stream().map(Generated::row).toList());
        int uncategorized = (int) rows.stream()
                .filter(g -> BucketOracle.bucket(g.row()) == TxnBucket.UNCATEGORIZED).count();
        int pending = (int) rows.stream().filter(Generated::reviewPending).count();
        Map<String, BigDecimal[]> money = Map.of(
                "income", new BigDecimal[] {actual.income(), expected.income()},
                "spending", new BigDecimal[] {actual.spending(), expected.spending()},
                "refunds", new BigDecimal[] {actual.refunds().net(), expected.refunds()},
                "unlinked", new BigDecimal[] {actual.unlinkedTransfers().net(), expected.unlinked()},
                "linked", new BigDecimal[] {actual.linkedTransfers().net(), expected.linked()},
                "netChange", new BigDecimal[] {actual.netChange(), expected.sum()});
        money.forEach((name, pair) -> {
            if (pair[0].compareTo(pair[1]) != 0) {
                mismatches.add(where + " " + name + ": " + pair[0] + ", oracle " + pair[1]);
            }
        });
        Map<String, int[]> counts = Map.of(
                "txnCount", new int[] {actual.txnCount(), rows.size()},
                "uncategorized", new int[] {actual.dataQuality().uncategorizedCount(), uncategorized},
                "reviewPending", new int[] {actual.dataQuality().reviewPendingCount(), pending});
        counts.forEach((name, pair) -> {
            if (pair[0] != pair[1]) {
                mismatches.add(where + " " + name + ": " + pair[0] + ", oracle " + pair[1]);
            }
        });
    }

    /** One-sided links, as in {@code TxnBucketSqlTest}: the trigger sets {@code is_transfer}, as in production. */
    private void linkTheLinkedOnes(List<Generated> generated) {
        Map<String, Long> ids = jdbc.queryForList("SELECT id, fingerprint FROM txn WHERE fingerprint LIKE 'random-%'")
                .stream().collect(Collectors.toMap(r -> (String) r.get("fingerprint"), r -> (Long) r.get("id")));
        List<Object[]> outs = new ArrayList<>();
        List<Object[]> ins = new ArrayList<>();
        for (int i = 0; i < generated.size(); i++) {
            Row row = generated.get(i).row();
            if (row.linked()) {
                (row.moneyOut() ? outs : ins).add(new Object[] {ids.get("random-" + i)});
            }
        }
        jdbc.batchUpdate("INSERT INTO transfer_link (out_txn_id, method, status) VALUES (?, 'AUTO', 'UNMATCHED')",
                outs);
        jdbc.batchUpdate("INSERT INTO transfer_link (in_txn_id, method, status) VALUES (?, 'AUTO', 'UNMATCHED')",
                ins);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM txn WHERE is_transfer AND fingerprint LIKE 'random-%'",
                Integer.class)).isEqualTo(outs.size() + ins.size());
    }

    private long account(String lastFour) {
        return jdbc.queryForObject("""
                INSERT INTO account (name, institution, type, last_four)
                VALUES ('Random ' || ?, 'Random Bank', 'CHECKING', ?) RETURNING id
                """, Long.class, lastFour, lastFour);
    }

    /** One statement over the whole range, so every month is complete. */
    private long statement(long account) {
        return jdbc.queryForObject("""
                INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash, parser)
                VALUES (?, ?, ?, 'random.csv', 'random-hash-' || ?, 'csv') RETURNING id
                """, Long.class, account, RANGE.firstDay(), RANGE.to().atEndOfMonth(), account);
    }
}
