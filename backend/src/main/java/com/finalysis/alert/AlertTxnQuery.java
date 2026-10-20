package com.finalysis.alert;

import com.finalysis.analytics.TxnBucketSql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The txns behind an alert, as {@link TxnRef}s plus their fingerprints (the keys' facts). Plain SQL over {@code txn},
 * never a raw description; runs in the caller's read-only transaction.
 */
@Component
class AlertTxnQuery {

    /** A txn and its fingerprint. */
    record Row(TxnRef ref, String fingerprint) {
    }

    static final String CATEGORY_MONTH_SQL = "SELECT t.id, t.account_id, t.txn_date, t.amount, t.merchant, t.fingerprint"
            + "\nFROM " + TxnBucketSql.FROM + "\nWHERE " + TxnBucketSql.BUCKET + " = 'EXPENSE'" + """

              AND t.category_id = :categoryId
              AND t.txn_date >= :from AND t.txn_date < :dayAfter
            ORDER BY t.amount, t.txn_date, t.id
            """;

    static final String BY_ID_SQL = """
            SELECT t.id, t.account_id, t.txn_date, t.amount, t.merchant, t.fingerprint
            FROM txn t
            WHERE t.id IN (:ids)
            """;

    private final JdbcClient jdbc;

    AlertTxnQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A category's spending rows in a month (the {@code EXPENSE} bucket), largest charge first. */
    List<Row> categoryMonth(long categoryId, YearMonth month) {
        return jdbc.sql(CATEGORY_MONTH_SQL)
                .param("categoryId", categoryId)
                .param("from", month.atDay(1))
                .param("dayAfter", month.plusMonths(1).atDay(1))
                .query((rs, n) -> row(rs)).list();
    }

    /** The txns with these ids, by id. */
    Map<Long, Row> byId(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql(BY_ID_SQL).param("ids", ids).query((rs, n) -> row(rs)).list().stream()
                .collect(Collectors.toMap(r -> r.ref().id(), Function.identity()));
    }

    static Row row(ResultSet rs) throws SQLException {
        return new Row(new TxnRef(rs.getLong("id"), rs.getLong("account_id"),
                rs.getObject("txn_date", LocalDate.class), rs.getBigDecimal("amount"), rs.getString("merchant")),
                rs.getString("fingerprint"));
    }
}
