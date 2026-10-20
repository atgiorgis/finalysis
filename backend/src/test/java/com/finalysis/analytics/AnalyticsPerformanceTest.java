package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.support.IntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * docs/plans/phase-5.md §9.6: 50,000 generated txns, about ten years of a busy household, over two accounts. The
 * household month-range query must use {@code ix_txn_date} (V18), and each endpoint must answer within 500 ms on a
 * warm database. The limit is generous so it doesn't flake: it is there to catch a missing index or a query per
 * month, not to benchmark.
 *
 * <p>The rows are generated once for the class, committed (the planner needs {@code ANALYZE} to see them) and deleted
 * afterwards.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsPerformanceTest {

    private static final int TXNS = 50_000;
    private static final LocalDate FIRST = LocalDate.parse("2017-01-01");
    private static final LocalDate LAST = LocalDate.parse("2026-12-31");
    private static final Duration LIMIT = Duration.ofMillis(500);

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbc;
    private final JdbcClient client;

    AnalyticsPerformanceTest(MockMvc mockMvc, JdbcTemplate jdbc, JdbcClient client) {
        this.mockMvc = mockMvc;
        this.jdbc = jdbc;
        this.client = client;
    }

    @BeforeAll
    void generate() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM statement", Integer.class)).isZero();
        List<String> lastFours = List.of("9921", "9922");
        for (int a = 0; a < lastFours.size(); a++) {
            String lastFour = lastFours.get(a);
            long account = jdbc.queryForObject("""
                    INSERT INTO account (name, institution, type, last_four)
                    VALUES ('Perf ' || ?, 'Perf Bank', 'CHECKING', ?) RETURNING id
                    """, Long.class, lastFour, lastFour);
            long statement = jdbc.queryForObject("""
                    INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash, parser)
                    VALUES (?, ?, ?, 'perf.csv', 'perf-hash-' || ?, 'csv') RETURNING id
                    """, Long.class, account, FIRST, LAST, lastFour);
            // Half the rows each, spread over every day of the ten years, every category kind and none, both signs.
            jdbc.update("""
                    INSERT INTO txn (account_id, statement_id, txn_date, raw_description, amount, source_section,
                                     category_id, category_source, fingerprint)
                    SELECT ?, ?, ?::date + (g * 37 % (?::date - ?::date + 1)), 'PERF CASE',
                           ((g * 7919 % 100001) - 50000) / 100.00, 'OTHER', c.id,
                           CASE WHEN c.id IS NULL THEN NULL ELSE 'USER' END, 'perf-' || g
                    FROM generate_series(?, ?) g
                    LEFT JOIN category c ON c.name = (ARRAY['Dining', 'Housing', 'Income', 'Transfer',
                                                           'Refunds & Rebates', NULL])[g % 6 + 1]
                    """, account, statement, FIRST, LAST, FIRST, a * TXNS / 2 + 1, (a + 1) * TXNS / 2);
        }
        jdbc.update("""
                INSERT INTO transfer_link (out_txn_id, method, status)
                SELECT id, 'AUTO', 'UNMATCHED' FROM txn WHERE fingerprint LIKE 'perf-%' AND amount < 0 AND id % 10 = 0
                """);
        jdbc.execute("ANALYZE txn");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM txn", Integer.class)).isEqualTo(TXNS);
    }

    @AfterAll
    void delete() {
        jdbc.update("DELETE FROM txn WHERE fingerprint LIKE 'perf-%'");
        jdbc.update("DELETE FROM statement WHERE file_hash LIKE 'perf-hash-%'");
        jdbc.update("DELETE FROM account WHERE institution = 'Perf Bank'");
        jdbc.execute("ANALYZE txn");
    }

    @Test
    void theHouseholdMonthRangeQueryUsesTheDateIndex() {
        MonthRange sixMonths = new MonthRange(YearMonth.of(2026, 7), YearMonth.of(2026, 12));

        List<String> plan = client.sql("EXPLAIN " + BucketTotalsQuery.CELLS_SQL + BucketTotalsQuery.GROUPING)
                .param("from", sixMonths.firstDay())
                .param("dayAfter", sixMonths.dayAfter())
                .query(String.class)
                .list();

        assertThat(String.join("\n", plan)).contains("ix_txn_date");
    }

    @Test
    void everyEndpointAnswersWithinTheLimit() throws Exception {
        List<String> requests = List.of(
                "/api/analytics/months",
                "/api/analytics/months?from=2025-01&to=2026-12",
                "/api/analytics/categories",
                "/api/analytics/categories?from=2025-01&to=2026-12",
                "/api/analytics/accounts",
                "/api/analytics/accounts?from=2025-01&to=2026-12",
                "/api/analytics/coverage");
        for (String request : requests) {
            mockMvc.perform(get(request)).andExpect(status().isOk()); // warm up
        }
        for (String request : requests) {
            long start = System.nanoTime();
            mockMvc.perform(get(request)).andExpect(status().isOk());
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as(request).isLessThan(LIMIT);
        }
    }
}
