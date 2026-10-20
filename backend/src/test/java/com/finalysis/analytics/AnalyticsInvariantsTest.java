package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * docs/plans/phase-5.md §9.2's invariants on the Phase 5 fixture and on the sample data, each imported for real:
 * <ul>
 *   <li><b>nothing dropped:</b> in every month, for the household and every account, the buckets add up to the plain
 *       sum of every amount ({@code income + spending + unlinkedTransfers.net + linkedTransfers.net = netChange}), the
 *       bucket counts to the txn count, and the category breakdown's rows to the same sum and count;</li>
 *   <li><b>balances:</b> for every statement that passed its balance checks and spans exactly one calendar month,
 *       the account's {@code netChange} for that month is {@code closing − opening} (ING-06). Card cycles (15th to
 *       14th) span two months, so a monthly figure can't be compared with them;</li>
 *   <li><b>transfers:</b> the household's {@code linkedTransfers.net} is 0.00 in every month whose links are all
 *       matched with both sides in the month.</li>
 * </ul>
 * The plain sums come from a SQL query that knows nothing of buckets. The randomized check is
 * {@link AnalyticsRandomizedTest}.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AnalyticsInvariantsTest {

    private static final MonthRange FIXTURE = new MonthRange(YearMonth.of(2025, 12), YearMonth.of(2026, 4));
    private static final MonthRange SAMPLE = new MonthRange(YearMonth.of(2026, 3), YearMonth.of(2026, 10));

    private final ImportTestSupport support;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final MonthlyTotalsService months;
    private final AccountTotalsService accounts;
    private final CategoryTotalsService categories;

    AnalyticsInvariantsTest(MockMvc mockMvc, AccountRepository accountRepository, JdbcTemplate jdbc,
                            JsonMapper jsonMapper, MonthlyTotalsService months, AccountTotalsService accounts,
                            CategoryTotalsService categories) {
        this.support = new ImportTestSupport(mockMvc, accountRepository, jdbc);
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.months = months;
        this.accounts = accounts;
        this.categories = categories;
    }

    @BeforeEach
    void databaseHasNoStatements() {
        assertThat(support.count("SELECT count(*) FROM statement")).isZero();
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @Test
    void theFixtureDropsNothing() throws Exception {
        Map<String, Long> ids = new AnalyticsFixture(support, jdbc, jsonMapper).load().accounts();

        assertNothingDropped(FIXTURE, List.copyOf(ids.values()));
    }

    @Test
    void theSampleDataDropsNothing() throws Exception {
        Map<String, Long> ids = new SampleDataImport(support, jsonMapper).load();

        assertNothingDropped(SAMPLE, List.copyOf(ids.values()));
    }

    @Test
    void theFixturesMonthlyStatementsMatchTheirBalances() throws Exception {
        new AnalyticsFixture(support, jdbc, jsonMapper).load();

        assertThat(assertBalances(FIXTURE)).as("checking January, February and April; March failed")
                .isEqualTo(3);
    }

    @Test
    void theSampleDatasMonthlyStatementsMatchTheirBalances() throws Exception {
        new SampleDataImport(support, jsonMapper).load();

        assertThat(assertBalances(SAMPLE)).as("checking, savings and Summit card, April to September")
                .isEqualTo(18);
    }

    @Test
    void linkedTransfersNetToZeroForTheHousehold() throws Exception {
        new AnalyticsFixture(support, jdbc, jsonMapper).load();
        assertThat(assertLinkedNetZero(FIXTURE)).as("January to April").isEqualTo(4);
    }

    @Test
    void linkedTransfersNetToZeroForTheHouseholdInTheSampleData() throws Exception {
        new SampleDataImport(support, jsonMapper).load();
        assertThat(assertLinkedNetZero(SAMPLE)).isPositive();
    }

    // ------------------------------------------------------------------ checks

    private void assertNothingDropped(MonthRange range, List<Long> accountIds) {
        List<String> problems = new ArrayList<>();
        List<Optional<Long>> scopes = new ArrayList<>();
        scopes.add(Optional.empty());
        accountIds.forEach(id -> scopes.add(Optional.of(id)));

        int monthsChecked = 0;
        for (Optional<Long> scope : scopes) {
            List<MonthTotals> totals = months.months(Optional.of(range.from()), Optional.of(range.to()), scope)
                    .months();
            CategoryTotalsResponse breakdown = categories.categories(Optional.of(range.from()),
                    Optional.of(range.to()), scope, Optional.empty(), false);
            for (MonthTotals m : totals) {
                monthsChecked++;
                String where = scope.map(id -> "account " + id).orElse("household") + " " + m.month();
                Plain plain = plain(m.month(), scope);
                BigDecimal buckets = m.income().add(m.spending()).add(m.unlinkedTransfers().net())
                        .add(m.linkedTransfers().net());
                int bucketCount = m.dataQuality().countedTxns() + m.unlinkedTransfers().count()
                        + m.linkedTransfers().count();
                check(problems, where + " buckets", buckets, plain.sum());
                check(problems, where + " netChange", m.netChange(), plain.sum());
                check(problems, where + " counts", bucketCount, plain.count());
                check(problems, where + " txnCount", m.txnCount(), plain.count());

                BigDecimal rows = BigDecimal.ZERO;
                int rowCount = 0;
                for (CategoryTotalsResponse.Row row : breakdown.rows()) {
                    MoneyFlow cell = MonthlyTotalsTest.cell(row, m.month());
                    rows = rows.add(cell.net());
                    rowCount += cell.count();
                }
                check(problems, where + " category rows", rows, plain.sum());
                check(problems, where + " category row counts", rowCount, plain.count());
            }
        }
        assertThat(problems).isEmpty();
        assertThat(monthsChecked).isEqualTo(range.length() * scopes.size());
        assertThat(plain(range, Optional.empty()).count()).as("the data is in the range checked")
                .isEqualTo(support.count("SELECT count(*) FROM txn"));
    }

    /** Returns how many statements were compared. */
    private int assertBalances(MonthRange range) {
        Map<Long, Map<YearMonth, BigDecimal>> netChange = new HashMap<>();
        accounts.accounts(Optional.of(range.from()), Optional.of(range.to())).accounts().forEach(account -> {
            Map<YearMonth, BigDecimal> byMonth = new HashMap<>();
            account.months().forEach(m -> byMonth.put(m.month(), m.netChange()));
            netChange.put(account.accountId(), byMonth);
        });

        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (Map<String, Object> statement : jdbc.queryForList("""
                SELECT id, account_id, period_start, period_end, opening_balance, closing_balance
                FROM statement WHERE balance_check = 'PASSED' ORDER BY id""")) {
            LocalDate start = ((Date) statement.get("period_start")).toLocalDate();
            LocalDate end = ((Date) statement.get("period_end")).toLocalDate();
            YearMonth month = YearMonth.from(start);
            if (!start.equals(month.atDay(1)) || !end.equals(month.atEndOfMonth())) {
                continue;
            }
            compared++;
            BigDecimal movement = ((BigDecimal) statement.get("closing_balance"))
                    .subtract((BigDecimal) statement.get("opening_balance"));
            check(problems, "statement " + statement.get("id") + " " + month,
                    netChange.get((Long) statement.get("account_id")).get(month), movement);
        }
        assertThat(problems).isEmpty();
        return compared;
    }

    /** Returns how many months qualified. */
    private int assertLinkedNetZero(MonthRange range) {
        List<String> problems = new ArrayList<>();
        int qualified = 0;
        for (MonthTotals m : months.months(Optional.of(range.from()), Optional.of(range.to()), Optional.empty())
                .months()) {
            LocalDate from = m.month().atDay(1);
            LocalDate dayAfter = m.month().plusMonths(1).atDay(1);
            // Links touching the month that aren't matched with both sides in it.
            Integer open = jdbc.queryForObject("""
                    SELECT count(*) FROM transfer_link l
                    LEFT JOIN txn o ON o.id = l.out_txn_id
                    LEFT JOIN txn i ON i.id = l.in_txn_id
                    WHERE (o.txn_date >= ?1 AND o.txn_date < ?2 OR i.txn_date >= ?1 AND i.txn_date < ?2)
                      AND NOT (l.status = 'MATCHED' AND o.txn_date >= ?1 AND o.txn_date < ?2
                               AND i.txn_date >= ?1 AND i.txn_date < ?2)
                    """.replace("?1", "'" + from + "'::date").replace("?2", "'" + dayAfter + "'::date"),
                    Integer.class);
            if (open > 0) {
                continue;
            }
            if (m.linkedTransfers().count() == 0) {
                continue;
            }
            qualified++;
            check(problems, "household " + m.month(), m.linkedTransfers().net(), new BigDecimal("0.00"));
        }
        assertThat(problems).isEmpty();
        return qualified;
    }

    private record Plain(BigDecimal sum, int count) {
    }

    /** The plain sum and count of the month's txns: no bucket, no category, no link. */
    private Plain plain(YearMonth month, Optional<Long> accountId) {
        return plain(new MonthRange(month, month), accountId);
    }

    private Plain plain(MonthRange range, Optional<Long> accountId) {
        String sql = "SELECT coalesce(sum(amount), 0.00) AS total, count(*) AS n FROM txn WHERE txn_date >= ? "
                + "AND txn_date < ?" + (accountId.isPresent() ? " AND account_id = ?" : "");
        Object[] args = accountId.isPresent()
                ? new Object[] {range.from().atDay(1), range.to().plusMonths(1).atDay(1), accountId.get()}
                : new Object[] {range.from().atDay(1), range.to().plusMonths(1).atDay(1)};
        return jdbc.queryForObject(sql, (rs, row) -> new Plain(rs.getBigDecimal("total"), rs.getInt("n")), args);
    }

    private static void check(List<String> problems, String what, BigDecimal actual, BigDecimal expected) {
        if (actual == null || actual.compareTo(expected) != 0) {
            problems.add(what + ": " + actual + ", expected " + expected);
        }
    }

    private static void check(List<String> problems, String what, int actual, int expected) {
        if (actual != expected) {
            problems.add(what + ": " + actual + ", expected " + expected);
        }
    }
}
