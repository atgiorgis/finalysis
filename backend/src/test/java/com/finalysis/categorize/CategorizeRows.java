package com.finalysis.categorize;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Synthetic accounts, statements and txns in plain SQL for categorization tests, with the merchant set as
 * cleaning would set it. Callers roll back or delete the accounts ({@link #deleteAccounts()}).
 */
public class CategorizeRows {

    private final JdbcTemplate jdbc;
    private final MerchantCleaner cleaner;
    private final List<Long> accounts = new ArrayList<>();
    private Long account;
    private Long statement;

    public CategorizeRows(JdbcTemplate jdbc, MerchantCleaner cleaner) {
        this.jdbc = jdbc;
        this.cleaner = cleaner;
    }

    /** Creates an account with one statement; later txns go to it. */
    public long account(String name, String type, String lastFour) {
        account = jdbc.queryForObject("""
                INSERT INTO account (name, institution, type, last_four)
                VALUES (?, 'Test Bank', ?, ?) RETURNING id""", Long.class, name, type, lastFour);
        accounts.add(account);
        statement = jdbc.queryForObject("""
                INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash, parser)
                VALUES (?, DATE '2026-04-01', DATE '2026-06-30', 'test.csv', ?, 'fixture') RETURNING id""",
                Long.class, account, "hash-" + UUID.randomUUID());
        return account;
    }

    /** A txn on the current account (one is created first if needed); the merchant is cleaned from the description. */
    public long txn(String description, String amount) {
        return txn(description, amount, "2026-04-10");
    }

    public long txn(String description, String amount, String date) {
        if (statement == null) {
            account("Categorize Test", "CHECKING", "4242");
        }
        return jdbc.queryForObject("""
                INSERT INTO txn (account_id, statement_id, txn_date, raw_description, merchant, amount, source_section,
                                 fingerprint)
                VALUES (?, ?, ?::date, ?, ?, ?::numeric, ?, ?) RETURNING id""",
                Long.class, account, statement, date, description, cleaner.clean(description).merchant(), amount,
                amount.startsWith("-") ? "WITHDRAWAL" : "DEPOSIT", "fp-" + UUID.randomUUID());
    }

    public long statementId() {
        return statement;
    }

    /** The txn's category name, or null. */
    public String category(long txnId) {
        return jdbc.queryForObject("""
                SELECT c.name FROM txn t LEFT JOIN category c ON c.id = t.category_id WHERE t.id = ?""",
                String.class, txnId);
    }

    public String source(long txnId) {
        return jdbc.queryForObject("SELECT category_source FROM txn WHERE id = ?", String.class, txnId);
    }

    public Long ruleId(long txnId) {
        return jdbc.queryForObject("SELECT category_rule_id FROM txn WHERE id = ?", Long.class, txnId);
    }

    /** Sets a category as the user or the AI would, bypassing the API. */
    public void categorize(long txnId, String category, String source) {
        jdbc.update("""
                UPDATE txn SET category_id = (SELECT id FROM category WHERE name = ?), category_source = ?,
                               category_rule_id = NULL, categorized_at = now()
                WHERE id = ?""", category, source, txnId);
    }

    public long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM category WHERE name = ?", Long.class, name);
    }

    /** The seed with this pattern and direction. */
    public long seed(String pattern, String direction) {
        return jdbc.queryForObject("""
                SELECT id FROM categorization_rule WHERE source = 'SEED' AND pattern = ? AND direction = ?""",
                Long.class, pattern, direction);
    }

    public void deleteAccounts() {
        for (long id : accounts) {
            jdbc.update("DELETE FROM txn WHERE account_id = ?", id);
            jdbc.update("DELETE FROM statement WHERE account_id = ?", id);
            jdbc.update("DELETE FROM account WHERE id = ?", id);
        }
        accounts.clear();
        account = null;
        statement = null;
    }
}
