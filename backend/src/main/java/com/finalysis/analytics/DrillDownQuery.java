package com.finalysis.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The txns behind a total, with the bucket {@link TxnBucketSql#BUCKET} gives each, so a drill-down and the total it
 * comes from use the same rule. One filtered subquery for the page and for its sums.
 */
@Component
class DrillDownQuery {

    static final String INNER_SQL = "SELECT t.id, t.account_id, t.txn_date, t.amount, t.merchant, t.category_id, "
            + TxnBucketSql.BUCKET + " AS bucket\nFROM " + TxnBucketSql.FROM
            + "\nWHERE t.txn_date >= :from AND t.txn_date < :dayAfter\n";

    /** One txn of the page; {@code categoryId} null when uncategorized. */
    record Row(long id, long accountId, LocalDate txnDate, BigDecimal amount, String merchant, Long categoryId,
               TxnBucket bucket) {
    }

    private final JdbcClient jdbc;

    DrillDownQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> page(MonthRange range, DrillDownFilter filter) {
        String sql = "SELECT b.* FROM (" + inner(filter) + ") b\nWHERE " + outer(filter)
                + "\nORDER BY b.txn_date DESC, b.id DESC\nLIMIT :size OFFSET :offset";
        return params(jdbc.sql(sql), range, filter)
                .param("size", filter.size())
                .param("offset", (long) filter.page() * filter.size())
                .query((rs, row) -> new Row(rs.getLong("id"), rs.getLong("account_id"),
                        rs.getObject("txn_date", LocalDate.class), rs.getBigDecimal("amount"),
                        rs.getString("merchant"), rs.getObject("category_id", Long.class),
                        TxnBucket.valueOf(rs.getString("bucket"))))
                .list();
    }

    MoneyFlow total(MonthRange range, DrillDownFilter filter) {
        String sql = """
                SELECT coalesce(sum(b.amount) FILTER (WHERE b.amount < 0), 0) AS money_out,
                       coalesce(sum(b.amount) FILTER (WHERE b.amount >= 0), 0) AS money_in,
                       count(*) AS n
                FROM (""" + inner(filter) + ") b\nWHERE " + outer(filter);
        return params(jdbc.sql(sql), range, filter)
                .query((rs, row) -> MoneyFlow.of(money(rs.getBigDecimal("money_out")),
                        money(rs.getBigDecimal("money_in")), rs.getInt("n")))
                .single();
    }

    /** The filters on txn columns, inside the subquery, so the indexes on account and date can be used. */
    private static String inner(DrillDownFilter filter) {
        StringBuilder sql = new StringBuilder(INNER_SQL);
        filter.accountId().ifPresent(id -> sql.append("  AND t.account_id = :accountId\n"));
        filter.categoryId().ifPresent(id -> sql.append("  AND t.category_id = :categoryId\n"));
        filter.merchant().ifPresent(m -> sql.append("  AND t.merchant = :merchant\n"));
        if (filter.noMerchant()) {
            sql.append("  AND t.merchant IS NULL\n");
        }
        return sql.toString();
    }

    /** The filters on the bucket, outside it. */
    private static String outer(DrillDownFilter filter) {
        StringBuilder sql = new StringBuilder("true");
        filter.bucket().ifPresent(b -> sql.append(" AND b.bucket = :bucket"));
        filter.measure().ifPresent(m -> sql.append(" AND ")
                .append(m == TrendMeasure.SPENT ? SpendingRowsSql.SPENDING : SpendingRowsSql.INCOME));
        return sql.toString();
    }

    private static JdbcClient.StatementSpec params(JdbcClient.StatementSpec spec, MonthRange range,
                                                   DrillDownFilter filter) {
        spec = spec.param("from", range.firstDay()).param("dayAfter", range.dayAfter());
        if (filter.accountId().isPresent()) {
            spec = spec.param("accountId", filter.accountId().get());
        }
        if (filter.categoryId().isPresent()) {
            spec = spec.param("categoryId", filter.categoryId().get());
        }
        if (filter.merchant().isPresent()) {
            spec = spec.param("merchant", filter.merchant().get());
        }
        if (filter.bucket().isPresent()) {
            spec = spec.param("bucket", filter.bucket().get().name());
        }
        return spec;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
}
