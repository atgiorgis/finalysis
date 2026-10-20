package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.analytics.BucketOracle.Kind;
import com.finalysis.analytics.BucketOracle.Row;
import com.finalysis.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TxnBucketSql} against {@link BucketOracle} on every combination the schema allows (docs/plans/phase-5.md
 * §1.1): linked or not, each kind of category or none, the spending-offset category, marked "not a transfer" or not,
 * and money out, zero and money in. 45 txns written straight to the tables, rolled back afterwards.
 */
@IntegrationTest
@Transactional
class TxnBucketSqlTest {

    /** A seeded category of each kind the rules tell apart; Refunds &amp; Rebates is the one with spending_offset. */
    private enum Category {
        NONE(null, null, false),
        EXPENSE("Groceries", Kind.EXPENSE, false),
        INCOME("Income", Kind.INCOME, false),
        TRANSFER("Transfer", Kind.TRANSFER, false),
        OFFSET("Refunds & Rebates", Kind.INCOME, true);

        final String name;
        final Kind kind;
        final boolean offset;

        Category(String name, Kind kind, boolean offset) {
            this.name = name;
            this.kind = kind;
            this.offset = offset;
        }
    }

    private static final List<BigDecimal> AMOUNTS =
            List.of(new BigDecimal("-10.00"), new BigDecimal("0.00"), new BigDecimal("10.00"));

    private final JdbcTemplate jdbc;

    TxnBucketSqlTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void theSqlBucketIsTheOraclesForEveryCombination() {
        long account = jdbc.queryForObject("""
                INSERT INTO account (name, institution, type, last_four)
                VALUES ('Bucket Test', 'Bucket Bank', 'CHECKING', '9901') RETURNING id
                """, Long.class);
        long statement = jdbc.queryForObject("""
                INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash, parser)
                VALUES (?, DATE '2026-01-01', DATE '2026-01-31', 'bucket.csv', 'bucket-test-hash', 'csv')
                RETURNING id
                """, Long.class, account);

        Map<Long, Row> rows = new LinkedHashMap<>();
        for (Category category : Category.values()) {
            for (boolean linked : List.of(false, true)) {
                for (boolean excluded : List.of(false, true)) {
                    if (linked && excluded) {
                        continue; // ck_txn_transfer_excluded
                    }
                    for (BigDecimal amount : AMOUNTS) {
                        long id = insertTxn(account, statement, category, excluded, amount, rows.size());
                        if (linked) {
                            link(id, amount);
                        }
                        rows.put(id, new Row(linked, category.kind, category.offset, excluded, amount));
                    }
                }
            }
        }
        assertThat(rows).hasSize(45);

        List<String> mismatches = new ArrayList<>();
        jdbc.query("SELECT t.id, " + TxnBucketSql.BUCKET + " AS bucket FROM " + TxnBucketSql.FROM
                + " WHERE t.statement_id = ?", rs -> {
            Row row = rows.get(rs.getLong("id"));
            TxnBucket expected = BucketOracle.bucket(row);
            TxnBucket actual = TxnBucket.valueOf(rs.getString("bucket"));
            if (actual != expected) {
                mismatches.add(row + ": SQL " + actual + ", oracle " + expected);
            }
        }, statement);
        assertThat(mismatches).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM txn WHERE statement_id = ?", Integer.class, statement))
                .isEqualTo(45);
    }

    private long insertTxn(long account, long statement, Category category, boolean excluded, BigDecimal amount,
                           int n) {
        Long categoryId = category.name == null ? null
                : jdbc.queryForObject("SELECT id FROM category WHERE name = ?", Long.class, category.name);
        return jdbc.queryForObject("""
                INSERT INTO txn (account_id, statement_id, txn_date, raw_description, amount, source_section,
                                 category_id, category_source, transfer_excluded, fingerprint)
                VALUES (?, ?, DATE '2026-01-15', 'BUCKET CASE', ?, 'OTHER', ?, ?, ?, ?)
                RETURNING id
                """, Long.class, account, statement, amount, categoryId, categoryId == null ? null : "USER",
                excluded, "bucket-test-" + n);
    }

    /** A one-sided link: the trigger then sets is_transfer, as in production. */
    private void link(long txnId, BigDecimal amount) {
        String side = amount.signum() < 0 ? "out_txn_id" : "in_txn_id";
        jdbc.update("INSERT INTO transfer_link (" + side + ", method, status) VALUES (?, 'AUTO', 'UNMATCHED')", txnId);
        assertThat(jdbc.queryForObject("SELECT is_transfer FROM txn WHERE id = ?", Boolean.class, txnId)).isTrue();
    }
}
