package com.finalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Checks the schema produced by the Flyway migrations. */
@IntegrationTest
@Transactional // each test's inserts roll back
class SchemaMigrationTest {

    private final JdbcTemplate jdbc;

    SchemaMigrationTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void initialMigrationApplied() {
        Boolean success = jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class);

        assertThat(success).isTrue();
    }

    @Test
    void vectorExtensionInstalled() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void allTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

        assertThat(tables).contains(
                "account", "statement", "category", "txn",
                "categorization_rule", "transfer_link", "merchant_embedding");
    }

    @ParameterizedTest
    @CsvSource({
            "account,             ck_account_type,                     c",
            "account,             ck_account_last_four,                c",
            "account,             ck_account_name_length,              c",
            "account,             ck_account_institution_length,       c",
            "statement,           ck_statement_status,                 c",
            "statement,           ck_statement_period,                 c",
            "statement,           ck_statement_section_totals_object,  c",
            "statement,           uq_statement_file_hash,              u",
            "statement,           uq_statement_account_period,         u",
            "category,            uq_category_name,                    u",
            "category,            ck_category_kind,                    c",
            "category,            fk_category_parent,                  f",
            "txn,                 fk_txn_statement_account,            f",
            "txn,                 fk_txn_category,                     f",
            "txn,                 ck_txn_source_section,               c",
            "txn,                 ck_txn_category_source,              c",
            "txn,                 ck_txn_category_has_source,          c",
            "txn,                 uq_txn_fingerprint,                  u",
            "categorization_rule, ck_categorization_rule_match_type,   c",
            "categorization_rule, ck_categorization_rule_source,       c",
            "transfer_link,       uq_transfer_link_out,                u",
            "transfer_link,       uq_transfer_link_in,                 u",
            "transfer_link,       ck_transfer_link_distinct,           c",
            "transfer_link,       ck_transfer_link_method,             c",
            "transfer_link,       ck_transfer_link_status,             c",
            "transfer_link,       ck_transfer_link_status_matches_in,  c",
    })
    void constraintExists(String table, String constraint, String type) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conname = ? AND conrelid = ?::regclass AND contype = ?::"char"
                """, Integer.class, constraint, table, type);

        assertThat(count).as("%s on %s", constraint, table).isEqualTo(1);
    }

    @Test
    void txnIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'txn'", String.class);

        assertThat(indexes).contains(
                "ix_txn_account_date", "ix_txn_category", "ix_txn_transfer_date", "ix_txn_statement_section");
    }

    @Test
    void merchantEmbeddingHasHnswCosineIndex() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_merchant_embedding_hnsw'", String.class);

        assertThat(definition).contains("hnsw").contains("vector_cosine_ops");
    }

    @Test
    void embeddingHas1024Dimensions() {
        String type = jdbc.queryForObject("""
                SELECT format_type(atttypid, atttypmod) FROM pg_attribute
                WHERE attrelid = 'merchant_embedding'::regclass AND attname = 'embedding'
                """, String.class);

        assertThat(type).isEqualTo("vector(1024)");
    }

    // Behavioral checks: a constraint can exist and still be written wrong.

    @Test
    void rejectsNonDigitLastFour() {
        assertThatThrownBy(() -> insertAccount("12ab"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_account_last_four");
    }

    @Test
    void rejectsTxnWhoseAccountDiffersFromStatement() {
        long checking = insertAccount("1001");
        long savings = insertAccount("1002");
        long statement = insertStatement(checking);

        assertThatThrownBy(() -> insertTxn(savings, statement, "fp-mismatch"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_txn_statement_account");
    }

    @Test
    void rejectsTransferLinkedToItself() {
        long account = insertAccount("1001");
        long txn = insertTxn(account, insertStatement(account), "fp-self");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO transfer_link (out_txn_id, in_txn_id, method, status) VALUES (?, ?, 'USER', 'MATCHED')",
                txn, txn))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_transfer_link_distinct");
    }

    @Test
    void rejectsMatchedTransferWithoutInTxn() {
        long account = insertAccount("1001");
        long txn = insertTxn(account, insertStatement(account), "fp-unmatched");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO transfer_link (out_txn_id, method, status) VALUES (?, 'AUTO', 'MATCHED')", txn))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_transfer_link_status_matches_in");
    }

    @Test
    void rejectsCategoryWithoutSource() {
        long account = insertAccount("1001");
        long txn = insertTxn(account, insertStatement(account), "fp-no-source");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE txn SET category_id = (SELECT id FROM category WHERE name = 'Groceries') WHERE id = ?",
                txn))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_txn_category_has_source");
    }

    @Test
    void rejectsSourceWithoutCategory() {
        long account = insertAccount("1001");
        long txn = insertTxn(account, insertStatement(account), "fp-no-category");

        assertThatThrownBy(() -> jdbc.update("UPDATE txn SET category_source = 'AI' WHERE id = ?", txn))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_txn_category_has_source");
    }

    private long insertAccount(String lastFour) {
        return jdbc.queryForObject("""
                INSERT INTO account (name, institution, type, last_four)
                VALUES ('Test', 'Northfield Bank', 'CHECKING', ?) RETURNING id
                """, Long.class, lastFour);
    }

    private long insertStatement(long accountId) {
        return jdbc.queryForObject("""
                INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash)
                VALUES (?, DATE '2026-04-01', DATE '2026-04-30', 'test.csv', ?) RETURNING id
                """, Long.class, accountId, "hash-" + accountId);
    }

    private long insertTxn(long accountId, long statementId, String fingerprint) {
        return jdbc.queryForObject("""
                INSERT INTO txn (account_id, statement_id, txn_date, raw_description, amount,
                                 source_section, fingerprint)
                VALUES (?, ?, DATE '2026-04-02', 'TEST', -10.00, 'WITHDRAWAL', ?) RETURNING id
                """, Long.class, accountId, statementId, fingerprint);
    }
}
