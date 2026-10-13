package com.finalysis.transfer;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Synthetic accounts, statements, txns and links in plain SQL for transfer tests. Remembers the txns
 * it creates, so tests can ignore rows other tests leave behind. Callers roll back.
 */
class TransferRows {

    private final JdbcTemplate jdbc;
    private final Set<Long> txns = new HashSet<>();

    TransferRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every txn created through this instance. */
    Set<Long> txns() {
        return txns;
    }

    long account(String type, String lastFour) {
        return jdbc.queryForObject("""
                INSERT INTO account (name, institution, type, last_four)
                VALUES ('Test', 'Northfield Bank', ?, ?) RETURNING id
                """, Long.class, type, lastFour);
    }

    long statement(long accountId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash, parser, status)
                VALUES (?, DATE '2026-04-01', DATE '2026-04-30', 'test.csv', ?, 'fixture', ?) RETURNING id
                """, Long.class, accountId, "hash-" + UUID.randomUUID(), status);
    }

    /** No post date; WITHDRAWAL when money out, DEPOSIT when money in. */
    long txn(long accountId, String amount, LocalDate txnDate) {
        return txn(accountId, amount, txnDate, null, amount.startsWith("-") ? "WITHDRAWAL" : "DEPOSIT");
    }

    long txn(long accountId, String amount, LocalDate txnDate, String section) {
        return txn(accountId, amount, txnDate, null, section);
    }

    /** Each txn gets its own statement. */
    long txn(long accountId, String amount, LocalDate txnDate, LocalDate postDate, String section) {
        return txn(accountId, statement(accountId, "IMPORTED"), amount, txnDate, postDate, section);
    }

    long txn(long accountId, long statementId, String amount, LocalDate txnDate, LocalDate postDate,
             String section) {
        long id = jdbc.queryForObject("""
                INSERT INTO txn (account_id, statement_id, txn_date, post_date, raw_description, amount,
                                 source_section, fingerprint)
                VALUES (?, ?, ?, ?, 'NORTHFIELD ONLINE TRANSFER', ?::numeric, ?, ?) RETURNING id
                """, Long.class, accountId, statementId, txnDate, postDate, amount, section,
                "fp-" + UUID.randomUUID());
        txns.add(id);
        return id;
    }

    /** A USER link, confirmed; MATCHED needs both sides, UNMATCHED one. */
    long userLink(Long out, Long in, String status) {
        return jdbc.queryForObject("""
                INSERT INTO transfer_link (out_txn_id, in_txn_id, method, status, confirmed_at)
                VALUES (?, ?, 'USER', ?, now()) RETURNING id
                """, Long.class, out, in, status);
    }

    void reject(long out, long in) {
        jdbc.update("INSERT INTO transfer_rejection (out_txn_id, in_txn_id) VALUES (?, ?)", out, in);
    }

    void exclude(long txnId) {
        jdbc.update("UPDATE txn SET transfer_excluded = true WHERE id = ?", txnId);
    }

    boolean isTransfer(long txnId) {
        return jdbc.queryForObject("SELECT is_transfer FROM txn WHERE id = ?", Boolean.class, txnId);
    }
}
