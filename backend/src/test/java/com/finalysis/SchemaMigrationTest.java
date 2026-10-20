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
                "categorization_rule", "transfer_link", "transfer_rejection", "merchant_embedding", "csv_mapping",
                "app_setting");
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
            "statement,           ck_statement_parser,                 c",
            "statement,           ck_statement_import_warnings_array,  c",
            "statement,           ck_statement_reviewed,               c",
            "category,            uq_category_name,                    u",
            "category,            ck_category_kind,                    c",
            "category,            fk_category_parent,                  f",
            "txn,                 fk_txn_statement_account,            f",
            "txn,                 fk_txn_category,                     f",
            "txn,                 ck_txn_source_section,               c",
            "txn,                 ck_txn_category_source,              c",
            "txn,                 ck_txn_category_has_source,          c",
            "txn,                 uq_txn_fingerprint,                  u",
            "txn,                 ck_txn_external_id_length,           c",
            "txn,                 ck_txn_printed_amount_length,        c",
            "txn,                 ck_txn_transfer_excluded,            c",
            "categorization_rule, ck_categorization_rule_match_type,   c",
            "categorization_rule, ck_categorization_rule_source,       c",
            "categorization_rule, ck_categorization_rule_field,        c",
            "categorization_rule, ck_categorization_rule_direction,    c",
            "categorization_rule, ck_categorization_rule_priority,     c",
            "categorization_rule, ck_categorization_rule_pattern_length, c",
            "categorization_rule, ck_categorization_rule_pattern_upper, c",
            "txn,                 fk_txn_category_rule,                f",
            "txn,                 ck_txn_category_rule_source,         c",
            "txn,                 ck_txn_category_ai_provenance,       c",
            "txn,                 ck_txn_category_confidence,          c",
            "txn,                 ck_txn_ai_review_reason,             c",
            "txn,                 ck_txn_ai_review_has_time,           c",
            "txn,                 ck_txn_ai_review_uncategorized,      c",
            "transfer_link,       uq_transfer_link_out,                u",
            "transfer_link,       uq_transfer_link_in,                 u",
            "transfer_link,       ck_transfer_link_distinct,           c",
            "transfer_link,       ck_transfer_link_method,             c",
            "transfer_link,       ck_transfer_link_status,             c",
            "transfer_link,       ck_transfer_link_has_side,           c",
            "transfer_link,       ck_transfer_link_status_sides,       c",
            "transfer_link,       ck_transfer_link_user_confirmed,     c",
            "transfer_rejection,  pk_transfer_rejection,               p",
            "merchant_embedding,  pk_merchant_embedding,               p",
            "merchant_embedding,  ck_merchant_embedding_merchant,      c",
            "merchant_embedding,  ck_merchant_embedding_model,         c",
            "merchant_embedding,  ck_merchant_embedding_dimensions,    c",
            "merchant_embedding,  ck_merchant_embedding_input_sha256,  c",
            "transfer_rejection,  fk_transfer_rejection_out,           f",
            "transfer_rejection,  fk_transfer_rejection_in,            f",
            "transfer_rejection,  ck_transfer_rejection_distinct,      c",
            "csv_mapping,         pk_csv_mapping,                      p",
            "csv_mapping,         fk_csv_mapping_account,              f",
            "csv_mapping,         ck_csv_mapping_amount_mode,          c",
            "csv_mapping,         ck_csv_mapping_sign,                 c",
            "csv_mapping,         ck_csv_mapping_amount_columns,       c",
            "csv_mapping,         ck_csv_mapping_delimiter,            c",
            "csv_mapping,         ck_csv_mapping_encoding,             c",
            "csv_mapping,         ck_csv_mapping_skip_rows,            c",
            "csv_mapping,         ck_csv_mapping_header_signature,     c",
            "csv_mapping,         ck_csv_mapping_date_format_length,   c",
            "csv_mapping,         ck_csv_mapping_description_columns,  c",
            "csv_mapping,         ck_csv_mapping_column_length,        c",
            "csv_mapping,         ck_csv_mapping_column_indexes,       c",
            "unparsed_upload,     ck_unparsed_upload_resolution,       c",
            "unparsed_upload,     ck_unparsed_upload_resolution_kind,  c",
            "unparsed_upload,     ck_unparsed_upload_resolution_resolved_at, c",
            "unparsed_upload,     ck_unparsed_upload_resolution_statement,   c",
            "app_setting,         pk_app_setting,                      p",
            "app_setting,         ck_app_setting_key,                  c",
            "app_setting,         ck_app_setting_value_length,         c",
    })
    void constraintExists(String table, String constraint, String type) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conname = ? AND conrelid = ?::regclass AND contype = ?::"char"
                """, Integer.class, constraint, table, type);

        assertThat(count).as("%s on %s", constraint, table).isEqualTo(1);
    }

    @Test
    void accountIdentityIsUnique() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_account_identity'", String.class);

        assertThat(definition).startsWith("CREATE UNIQUE INDEX")
                .contains("lower(btrim(institution))", "type", "last_four")
                .doesNotContain("WHERE", "name");
    }

    @Test
    void txnIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'txn'", String.class);

        assertThat(indexes).contains(
                "ix_txn_account_date", "ix_txn_category", "ix_txn_transfer_date", "ix_txn_statement_section",
                "ix_txn_account_external_id", "ix_txn_merchant", "ix_txn_category_rule", "ix_txn_date");
    }

    /** V18: only Refunds & Rebates offsets spending (docs/plans/phase-5.md §1.2); new categories default to false. */
    @Test
    void onlyRefundsAndRebatesOffsetsSpending() {
        List<String> offsets = jdbc.queryForList("SELECT name FROM category WHERE spending_offset", String.class);
        assertThat(offsets).containsExactly("Refunds & Rebates");

        jdbc.update("INSERT INTO category (name, kind) VALUES ('V18 Probe', 'EXPENSE')");
        assertThat(jdbc.queryForObject("SELECT spending_offset FROM category WHERE name = 'V18 Probe'", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT kind FROM category WHERE name = 'Refunds & Rebates'", String.class))
                .as("the kind, which categorization reads, is unchanged").isEqualTo("INCOME");
    }

    @Test
    void activeUserRulesAreUniqueByPattern() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_categorization_rule_user_pattern'", String.class);

        assertThat(definition).startsWith("CREATE UNIQUE INDEX")
                .contains("field", "match_type", "pattern", "direction", "WHERE (active AND (source <> 'SEED'::text))");
    }

    @Test
    void aRuleCategoryNeedsTheRuleSource() {
        long account = insertAccount("1001");
        long txn = insertTxn(account, insertStatement(account), "fp-rule-source");
        Long rule = jdbc.queryForObject("SELECT min(id) FROM categorization_rule", Long.class);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE txn SET category_id = (SELECT id FROM category WHERE name = 'Groceries'), category_source = 'AI',
                               category_rule_id = ?
                WHERE id = ?""", rule, txn))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_txn_category_rule_source");
    }

    /** V16: the AI's model, confidence and prompt version exist only on an AI category, and only 0 to 1. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            RULE | category_model = 'm'                                 | ck_txn_category_ai_provenance
            USER | category_confidence = 0.5                            | ck_txn_category_ai_provenance
            RULE | category_prompt_version = '2'                        | ck_txn_category_ai_provenance
            AI   | category_confidence = 1.001                          | ck_txn_category_confidence
            AI   | category_confidence = -0.001                         | ck_txn_category_confidence
            AI   | ai_review_reason = 'LOW_CONFIDENCE', ai_review_at = now() | ck_txn_ai_review_uncategorized
            """)
    void aiColumnsFollowTheAiCategory(String source, String set, String constraint) {
        long account = insertAccount("1001");
        long txn = insertTxn(account, insertStatement(account), "fp-ai-" + constraint + set.length());

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE txn SET category_id = (SELECT id FROM category WHERE name = 'Groceries'), category_source = ?,
                               %s
                WHERE id = ?""".formatted(set), source, txn))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    @Test
    void anAiCategoryKeepsItsProvenanceAndAReviewNeedsAReasonAndATime() {
        long account = insertAccount("1001");
        long txn = insertTxn(account, insertStatement(account), "fp-ai-ok");

        jdbc.update("""
                UPDATE txn SET category_id = (SELECT id FROM category WHERE name = 'Groceries'), category_source = 'AI',
                               category_model = 'm', category_confidence = 0.875, category_prompt_version = '2'
                WHERE id = ?""", txn);
        jdbc.update("""
                UPDATE txn SET category_id = NULL, category_source = NULL, category_model = NULL,
                               category_confidence = NULL, category_prompt_version = NULL,
                               ai_review_reason = 'INVALID_ANSWER', ai_review_at = now()
                WHERE id = ?""", txn);

        assertThatThrownBy(() -> jdbc.update("UPDATE txn SET ai_review_at = NULL WHERE id = ?", txn))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_txn_ai_review_has_time");
        // The reason's values: EnumCheckConstraintTest.
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            CONTAINS | lower case     | 0    | ck_categorization_rule_pattern_upper
            CONTAINS | ''             | 0    | ck_categorization_rule_pattern_length
            CONTAINS | X              | 1001 | ck_categorization_rule_priority
            CONTAINS | X              | -1   | ck_categorization_rule_priority
            SOUNDEX  | X              | 0    | ck_categorization_rule_match_type
            """)
    void rejectsInvalidRules(String matchType, String pattern, int priority, String constraint) {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO categorization_rule (pattern, match_type, category_id, source, priority)
                VALUES (?, ?, (SELECT id FROM category WHERE name = 'Dining'), 'USER', ?)""",
                pattern, matchType, priority))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    /** A regex keeps its case: upper-casing \b would make it \B. */
    @Test
    void aRegexRuleMayHaveLowerCase() {
        jdbc.update("""
                INSERT INTO categorization_rule (pattern, match_type, category_id, source)
                VALUES ('\bcafe\b', 'REGEX', (SELECT id FROM category WHERE name = 'Dining'), 'USER')""");
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
    void rejectsSameAccountWithInstitutionInAnotherCaseOrSpacing() {
        insertAccount("1001");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO account (name, institution, type, last_four)
                VALUES ('Other', '  NORTHFIELD bank ', 'CHECKING', '1001')"""))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_account_identity");
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
                .hasMessageContaining("ck_transfer_link_status_sides");
    }

    @Test
    void replacedTransferConstraintIsGone() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'ck_transfer_link_status_matches_in'", Integer.class);

        assertThat(count).isZero();
    }

    @Test
    void transferLinkTriggersExist() {
        List<String> triggers = jdbc.queryForList(
                "SELECT tgname FROM pg_trigger WHERE tgrelid = 'transfer_link'::regclass AND NOT tgisinternal",
                String.class);

        assertThat(triggers).containsExactlyInAnyOrder(
                "trg_transfer_link_txn_once", "trg_transfer_link_sync_is_transfer");
    }

    @Test
    void transferRejectionInSideIsIndexed() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'transfer_rejection'", String.class);

        assertThat(indexes).contains("pk_transfer_rejection", "ix_transfer_rejection_in");
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

    @Test
    void samePeriodCanBeImportedTwice() {
        long account = insertAccount("1001");

        assertThat(insertStatement(account)).isNotEqualTo(insertStatement(account));
    }

    @Test
    void onlyANeedsReviewStatementCanBeReviewed() {
        long accountId = insertAccount("1001");
        String insert = """
                INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash, parser,
                                       status, reviewed_at)
                VALUES (?, DATE '2026-04-01', DATE '2026-04-30', 'a.csv', ?, 'fixture', ?, now())
                """;

        jdbc.update(insert, accountId, "hash-reviewed-1", "NEEDS_REVIEW");
        assertThatThrownBy(() -> jdbc.update(insert, accountId, "hash-reviewed-2", "IMPORTED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_statement_reviewed");
    }

    @Test
    void rejectsStatementWithoutParser() {
        long account = insertAccount("1001");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash)
                VALUES (?, DATE '2026-04-01', DATE '2026-04-30', 'x.ofx', 'hash-no-parser')""", account))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInvalidParserId() {
        long account = insertAccount("1001");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash, parser)
                VALUES (?, DATE '2026-04-01', DATE '2026-04-30', 'x.ofx', 'hash-bad-parser', 'OFX 1')""", account))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_statement_parser");
    }

    private long insertAccount(String lastFour) {
        return jdbc.queryForObject("""
                INSERT INTO account (name, institution, type, last_four)
                VALUES ('Test', 'Northfield Bank', 'CHECKING', ?) RETURNING id
                """, Long.class, lastFour);
    }

    private long insertStatement(long accountId) {
        return jdbc.queryForObject("""
                INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash, parser)
                VALUES (?, DATE '2026-04-01', DATE '2026-04-30', 'test.csv', ?, 'fixture') RETURNING id
                """, Long.class, accountId, "hash-" + accountId + "-" + System.nanoTime());
    }

    private long insertTxn(long accountId, long statementId, String fingerprint) {
        return jdbc.queryForObject("""
                INSERT INTO txn (account_id, statement_id, txn_date, raw_description, amount,
                                 source_section, fingerprint)
                VALUES (?, ?, DATE '2026-04-02', 'TEST', -10.00, 'WITHDRAWAL', ?) RETURNING id
                """, Long.class, accountId, statementId, fingerprint);
    }
}
