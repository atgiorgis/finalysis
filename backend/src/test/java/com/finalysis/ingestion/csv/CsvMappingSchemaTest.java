package com.finalysis.ingestion.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.support.IntegrationTest;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The V6 CHECK constraints on csv_mapping, written straight to the database: the API validates
 * the same rules first, so these are the last line of defence against a bug there.
 */
@IntegrationTest
@Transactional // each test's inserts roll back
class CsvMappingSchemaTest {

    private static final String SHA = "'sha256:" + "a".repeat(64) + "'";
    private static final Map<String, String> DEBIT_CREDIT = Map.of("amount_mode", "'DEBIT_CREDIT'",
            "amount_column", "NULL", "debit_column", "'Debit'", "credit_column", "'Credit'");

    private final JdbcTemplate jdbc;
    private long account;

    CsvMappingSchemaTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void createAccount() {
        account = jdbc.queryForObject("""
                INSERT INTO account (name, institution, type, last_four)
                VALUES ('Test', 'Northfield Bank', 'CHECKING', '1001') RETURNING id
                """, Long.class);
    }

    @Test
    void acceptsValidSingleAndIndexedMappings() {
        insert(Map.of());
        jdbc.update("DELETE FROM csv_mapping WHERE account_id = ?", account);
        insert(Map.of("header_row", "false", "header_signature", "'columns:5'", "date_column", "'0'",
                "description_columns", "ARRAY['2', '3']", "amount_column", "'4'"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM csv_mapping WHERE account_id = ?", Integer.class, account))
                .isEqualTo(1);
    }

    // Enum values are compared with the Java enums in EnumCheckConstraintTest. An unknown amount_mode
    // is not listed: it also breaks ck_csv_mapping_amount_columns, which Postgres may report first.
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "ck_csv_mapping_sign                 | sign                | 'NEGATED'",
            "ck_csv_mapping_amount_columns       | amount_column       | NULL",
            "ck_csv_mapping_amount_columns       | debit_column        | 'Debit'",
            "ck_csv_mapping_delimiter            | delimiter           | ',,'",
            "ck_csv_mapping_delimiter            | delimiter           | '\"'",
            "ck_csv_mapping_delimiter            | delimiter           | E'\\n'",
            "ck_csv_mapping_encoding             | encoding            | 'UTF-16'",
            "ck_csv_mapping_skip_rows            | skip_rows           | -1",
            "ck_csv_mapping_skip_rows            | skip_rows           | 101",
            "ck_csv_mapping_header_signature     | header_signature    | 'columns:5'",
            "ck_csv_mapping_header_signature     | header_signature    | 'sha256:abc'",
            "ck_csv_mapping_date_format_length   | date_format         | ''",
            "ck_csv_mapping_description_columns  | description_columns | ARRAY[]::text[]",
            "ck_csv_mapping_description_columns  | description_columns | ARRAY['Description', NULL]",
            "ck_csv_mapping_description_columns  | description_columns | ARRAY[['Description']]",
            "ck_csv_mapping_column_length        | date_column         | ''",
            "ck_csv_mapping_column_length        | balance_column      | ''",
    })
    void rejectsInvalidColumn(String constraint, String column, String value) {
        assertThatThrownBy(() -> insert(Map.of(column, value)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    @Test
    void acceptsDebitCreditMapping() {
        insert(DEBIT_CREDIT);
    }

    // One case per test: a failed insert aborts the test transaction.
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "credit_column | NULL",
            "debit_column  | NULL",
            "amount_column | 'Amount'",
    })
    void debitCreditNeedsBothColumnsAndNoAmountColumn(String column, String value) {
        Map<String, String> values = new HashMap<>(DEBIT_CREDIT);
        values.put(column, value);

        assertThatThrownBy(() -> insert(values))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_csv_mapping_amount_columns");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "date_column         | 'Date'",
            "date_column         | '01'",
            "date_column         | '1000'",
            "amount_column       | 'Amount'",
            "description_columns | ARRAY['2', 'Memo']",
    })
    void withoutHeaderRowEveryColumnIsAnIndex(String column, String value) {
        Map<String, String> indexed = new HashMap<>(Map.of("header_row", "false", "header_signature", "'columns:5'",
                "date_column", "'0'", "description_columns", "ARRAY['2']", "amount_column", "'4'"));
        indexed.put(column, value);

        assertThatThrownBy(() -> insert(indexed))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_csv_mapping_column_indexes");
    }

    @Test
    void oneMappingPerAccount() {
        insert(Map.of());

        assertThatThrownBy(() -> insert(Map.of()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("pk_csv_mapping");
    }

    @Test
    void mappingNeedsAnExistingAccount() {
        account = -1;

        assertThatThrownBy(() -> insert(Map.of()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_csv_mapping_account");
    }

    /** Inserts a valid SINGLE mapping with header row, with some columns replaced by SQL literals. */
    private void insert(Map<String, String> overrides) {
        Map<String, String> values = new HashMap<>(Map.ofEntries(
                Map.entry("account_id", Long.toString(account)),
                Map.entry("delimiter", "','"),
                Map.entry("encoding", "'UTF-8'"),
                Map.entry("header_row", "true"),
                Map.entry("skip_rows", "0"),
                Map.entry("header_signature", SHA),
                Map.entry("date_column", "'Date'"),
                Map.entry("date_format", "'MM/dd/yyyy'"),
                Map.entry("description_columns", "ARRAY['Description']"),
                Map.entry("amount_mode", "'SINGLE'"),
                Map.entry("amount_column", "'Amount'"),
                Map.entry("sign", "'AS_IS'")));
        values.putAll(overrides);
        String columns = String.join(", ", values.keySet());
        String literals = values.keySet().stream().map(values::get).collect(Collectors.joining(", "));
        jdbc.update("INSERT INTO csv_mapping (" + columns + ") VALUES (" + literals + ")");
    }
}
