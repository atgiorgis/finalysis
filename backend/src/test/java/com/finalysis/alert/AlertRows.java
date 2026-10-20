package com.finalysis.alert;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes accounts, statements and txns straight into the tables for the boundary tests, the
 * {@code AnalyticsRandomizedTest} idiom: the checks read only those tables, so a test can place a charge exactly one cent
 * or one day either side of a threshold. Each test is {@code @Transactional}, so everything is rolled back.
 */
final class AlertRows {

    private final JdbcTemplate jdbc;
    private final AtomicInteger sequence = new AtomicInteger();

    AlertRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A checking account with one statement over {@code from} to {@code to}, so every month of it is complete. */
    long account(String lastFour, LocalDate from, LocalDate to) {
        long account = jdbc.queryForObject("""
                INSERT INTO account (name, institution, type, last_four)
                VALUES ('Alert ' || ?, 'Alert Bank', 'CHECKING', ?) RETURNING id
                """, Long.class, lastFour, lastFour);
        jdbc.update("""
                INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash, parser)
                VALUES (?, ?, ?, 'alert.csv', 'alert-hash-' || ?, 'csv')
                """, account, from, to, account);
        return account;
    }

    long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM category WHERE name = ?", Long.class, name);
    }

    /** A txn with a USER category (null = uncategorized); the merchant is also its raw description. */
    long txn(long account, LocalDate date, String amount, Long categoryId, String merchant) {
        return txn(account, date, amount, categoryId, merchant, merchant, null);
    }

    long txn(long account, LocalDate date, String amount, Long categoryId, String merchant, String rawDescription,
             String externalId) {
        long statement = jdbc.queryForObject("SELECT id FROM statement WHERE account_id = ?", Long.class, account);
        return jdbc.queryForObject("""
                INSERT INTO txn (account_id, statement_id, txn_date, raw_description, merchant, amount, source_section,
                                 category_id, category_source, external_id, fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, 'OTHER', ?, ?, ?, ?) RETURNING id
                """, Long.class, account, statement, date, rawDescription, merchant, new BigDecimal(amount),
                categoryId, categoryId == null ? null : "USER", externalId,
                "alert-test-" + account + "-" + sequence.incrementAndGet());
    }

    /** Links a txn as a one-sided transfer, so it is in the {@code LINKED_TRANSFER} bucket. */
    void linkOneSided(long txnId, boolean moneyOut) {
        jdbc.update("INSERT INTO transfer_link (" + (moneyOut ? "out_txn_id" : "in_txn_id")
                + ", method, status) VALUES (?, 'AUTO', 'UNMATCHED')", txnId);
    }
}
