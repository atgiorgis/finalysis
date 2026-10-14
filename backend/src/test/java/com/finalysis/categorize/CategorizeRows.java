package com.finalysis.categorize;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Synthetic accounts, statements and txns in plain SQL for categorization tests, with the merchant set as
 * cleaning would set it. Callers roll back or delete the accounts ({@link #deleteAccounts()}).
 */
class CategorizeRows {

    private final JdbcTemplate jdbc;
    private final MerchantCleaner cleaner;
    private Long account;
    private Long statement;

    CategorizeRows(JdbcTemplate jdbc, MerchantCleaner cleaner) {
        this.jdbc = jdbc;
        this.cleaner = cleaner;
    }

    /** A txn on this instance's one account and statement; the merchant is cleaned from the description. */
    long txn(String description, String amount) {
        if (statement == null) {
            account = jdbc.queryForObject("""
                    INSERT INTO account (name, institution, type, last_four)
                    VALUES ('Categorize Test', 'Test Bank', 'CHECKING', '4242') RETURNING id""", Long.class);
            statement = jdbc.queryForObject("""
                    INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash, parser)
                    VALUES (?, DATE '2026-04-01', DATE '2026-04-30', 'test.csv', ?, 'fixture') RETURNING id""",
                    Long.class, account, "hash-" + UUID.randomUUID());
        }
        return jdbc.queryForObject("""
                INSERT INTO txn (account_id, statement_id, txn_date, raw_description, merchant, amount, source_section,
                                 fingerprint)
                VALUES (?, ?, DATE '2026-04-10', ?, ?, ?::numeric, ?, ?) RETURNING id""",
                Long.class, account, statement, description, cleaner.clean(description).merchant(), amount,
                amount.startsWith("-") ? "WITHDRAWAL" : "DEPOSIT", "fp-" + UUID.randomUUID());
    }

    long statementId() {
        return statement;
    }

    /** The txn's category name, or null. */
    String category(long txnId) {
        return jdbc.queryForObject("""
                SELECT c.name FROM txn t LEFT JOIN category c ON c.id = t.category_id WHERE t.id = ?""",
                String.class, txnId);
    }

    String source(long txnId) {
        return jdbc.queryForObject("SELECT category_source FROM txn WHERE id = ?", String.class, txnId);
    }

    Long ruleId(long txnId) {
        return jdbc.queryForObject("SELECT category_rule_id FROM txn WHERE id = ?", Long.class, txnId);
    }

    /** Sets a category as the user or the AI would, bypassing the API. */
    void categorize(long txnId, String category, String source) {
        jdbc.update("""
                UPDATE txn SET category_id = (SELECT id FROM category WHERE name = ?), category_source = ?,
                               category_rule_id = NULL, categorized_at = now()
                WHERE id = ?""", category, source, txnId);
    }

    long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM category WHERE name = ?", Long.class, name);
    }

    /** The seed with this pattern and direction. */
    long seed(String pattern, String direction) {
        return jdbc.queryForObject("""
                SELECT id FROM categorization_rule WHERE source = 'SEED' AND pattern = ? AND direction = ?""",
                Long.class, pattern, direction);
    }

    void deleteAccounts() {
        if (account != null) {
            jdbc.update("DELETE FROM txn WHERE account_id = ?", account);
            jdbc.update("DELETE FROM statement WHERE account_id = ?", account);
            jdbc.update("DELETE FROM account WHERE id = ?", account);
            account = null;
            statement = null;
        }
    }
}
