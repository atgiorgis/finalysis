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
                "app_setting", "alert_dismissal", "chat_conversation", "chat_turn", "chat_tool_call",
                "chat_usage");
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
            "chat_conversation,   pk_chat_conversation,                p",
            "chat_conversation,   ck_chat_conversation_title,          c",
            "chat_conversation,   ck_chat_conversation_summary,        c",
            "chat_turn,           fk_chat_turn_conversation,           f",
            "chat_turn,           uq_chat_turn_seq,                    u",
            "chat_turn,           ck_chat_turn_question,               c",
            "chat_turn,           ck_chat_turn_answer_source,          c",
            "chat_turn,           ck_chat_turn_grounding,              c",
            "chat_turn,           ck_chat_turn_answer,                 c",
            "chat_turn,           ck_chat_turn_limit,                  c",
            "chat_turn,           ck_chat_turn_citations_array,        c",
            "chat_turn,           ck_chat_turn_error,                  c",
            "chat_tool_call,      fk_chat_tool_call_turn,              f",
            "chat_tool_call,      uq_chat_tool_call_seq,               u",
            "chat_tool_call,      ck_chat_tool_call_status,            c",
            "chat_tool_call,      ck_chat_tool_call_arguments_object,  c",
            "chat_usage,          pk_chat_usage,                       p",
            "chat_usage,          ck_chat_usage_kind,                  c",
            "chat_usage,          ck_chat_usage_counts,                c",
            "chat_usage,          ck_chat_usage_cost,                  c",
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

    private long chatConversation() {
        return jdbc.queryForObject("INSERT INTO chat_conversation DEFAULT VALUES RETURNING id", Long.class);
    }

    private static final String CHAT_TURN = "INSERT INTO chat_turn (conversation_id, seq, question, outbound_question, "
            + "answer, answer_source, grounding_status, alias_map, model, prompt_version, rounds, tool_calls, millis, "
            + "error) VALUES (?, ?, 'q', 'q', ?, ?, ?, '{}', 'm', 'chat-1', 1, 0, 1, ?)";

    /** V21: a turn's answer, source, grounding and error agree; deleting a conversation deletes its turns and calls. */
    @Test
    void chatTurnsAreConsistentAndCascade() {
        long conversation = chatConversation();
        jdbc.update(CHAT_TURN, conversation, 1, "an answer", "MODEL", "GROUNDED", null);
        jdbc.update(CHAT_TURN, conversation, 2, "the list", "FALLBACK", "FALLBACK", null);
        jdbc.update(CHAT_TURN, conversation, 3, null, "ERROR", null, "SdkClientException");
        long turn = jdbc.queryForObject("SELECT id FROM chat_turn WHERE conversation_id = ? AND seq = 1", Long.class,
                conversation);
        jdbc.update("INSERT INTO chat_tool_call (turn_id, seq, tool, arguments, status, millis) "
                + "VALUES (?, 1, 'get_trends', '{}', 'OK', 3)", turn);

        jdbc.update("DELETE FROM chat_conversation WHERE id = ?", conversation);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_turn WHERE conversation_id = ?", Integer.class,
                conversation)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_tool_call WHERE turn_id = ?", Integer.class, turn))
                .isZero();
    }

    /** V21: an answer without a source that has one, a fallback not marked FALLBACK, an error without its class. */
    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "null,      MODEL,    GROUNDED,  null",
            "an answer, ERROR,    null,      SdkClientException",
            "an answer, FALLBACK, GROUNDED,  null",
            "an answer, MODEL,    FALLBACK,  null",
            "null,      ERROR,    null,      null",
            "an answer, MODEL,    GROUNDED,  SdkClientException",
            "an answer, MODEL,    null,      null"})
    void anInconsistentChatTurnIsRefused(String answer, String source, String grounding, String error) {
        long conversation = chatConversation();
        assertThatThrownBy(() -> jdbc.update(CHAT_TURN, conversation, 1, answer, source, grounding, error))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** V19: accounts are open (null) until the user closes them; existing accounts stay open. */
    @Test
    void accountsAreOpenUntilClosed() {
        assertThat(jdbc.queryForObject("""
                SELECT is_nullable || ' ' || data_type FROM information_schema.columns
                WHERE table_name = 'account' AND column_name = 'closed_on'""", String.class))
                .isEqualTo("YES date");
        jdbc.update("INSERT INTO account (name, institution, type, last_four) VALUES ('V19', 'V19 Bank', 'SAVINGS', '1919')");
        assertThat(jdbc.queryForObject("SELECT closed_on FROM account WHERE name = 'V19'", java.sql.Date.class))
                .isNull();
    }

    /** V20: a dismissal with a well-formed key and a known type is stored, with its time. */
    @Test
    void anAlertDismissalIsStored() {
        String key = "large-transaction.2026-08.0123456789abcdef";
        jdbc.update("INSERT INTO alert_dismissal (alert_key, alert_type) VALUES (?, 'LARGE_TRANSACTION')", key);
        assertThat(jdbc.queryForObject("SELECT dismissed_at IS NOT NULL FROM alert_dismissal WHERE alert_key = ?",
                Boolean.class, key)).isTrue();
    }

    /** V20: a malformed key, an unknown type, or an empty or too long note is refused. */
    @ParameterizedTest
    @CsvSource(value = {
            "LARGE.2026-08.0123456789abcdef, LARGE_TRANSACTION, NULL",
            "large-transaction.2026-8.0123456789abcdef, LARGE_TRANSACTION, NULL",
            "large-transaction.2026-08.0123456789ABCDEF, LARGE_TRANSACTION, NULL",
            "large-transaction.2026-08.0123456789abcde, LARGE_TRANSACTION, NULL",
            "large-transaction.2026-08.0123456789abcdef, BUDGET, NULL",
            "large-transaction.2026-08.0123456789abcdef, LARGE_TRANSACTION, ''",
            "large-transaction.2026-08.0123456789abcdef, LARGE_TRANSACTION, LONG"}, nullValues = "NULL")
    void aBadAlertDismissalIsRefused(String key, String type, String note) {
        String value = "LONG".equals(note) ? "x".repeat(201) : note;
        assertThatThrownBy(() -> jdbc.update("INSERT INTO alert_dismissal (alert_key, alert_type, note) VALUES (?, ?, ?)",
                key, type, value)).isInstanceOf(DataIntegrityViolationException.class);
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

    /** V25 (phase-9.md §4.1): no HNSW index, which needs one length; an index on the model serves the exact scan. */
    @Test
    void embeddingsHaveAModelIndexAndNoVectorIndex() {
        List<String> definitions = jdbc.queryForList("""
                SELECT indexdef FROM pg_indexes WHERE tablename IN ('merchant_embedding', 'search_embedding')""",
                String.class);

        assertThat(definitions).noneMatch(d -> d.contains("hnsw") || d.contains("ivfflat"));
        assertThat(definitions).anyMatch(d -> d.contains("ix_merchant_embedding_model") && d.contains("(model)"));
        assertThat(definitions).anyMatch(d -> d.contains("ix_search_embedding_kind_model") && d.contains("(kind, model)"));
    }

    /** V25: both tables take a vector of any length (Titan V2's 1024, nomic-embed-text's 768). */
    @Test
    void embeddingsTakeAnyDimension() {
        for (String table : List.of("merchant_embedding", "search_embedding")) {
            String type = jdbc.queryForObject("""
                    SELECT format_type(atttypid, atttypmod) FROM pg_attribute
                    WHERE attrelid = ?::regclass AND attname = 'embedding'
                    """, String.class, table);

            assertThat(type).as(table).isEqualTo("vector");
        }
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
