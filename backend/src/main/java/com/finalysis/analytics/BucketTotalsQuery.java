package com.finalysis.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Sums and counts per month, account, category and bucket: the one query every Phase 5 total starts from. The bucket
 * is {@link TxnBucketSql#BUCKET}, so the counting rules exist only there; the month is the calendar month of
 * {@code txn_date}, selected as a half-open range so {@code ix_txn_date} can be used (docs/plans/phase-5.md §1.5).
 * SQL only sums and counts {@code NUMERIC}, which is exact; Java adds the cells up and divides.
 */
@Component
class BucketTotalsQuery {

    static final String CELLS_SQL = "SELECT date_trunc('month', t.txn_date)::date AS month, t.account_id, "
            + "t.category_id,\n" + TxnBucketSql.BUCKET + " AS bucket," + """

                   coalesce(sum(t.amount) FILTER (WHERE t.amount < 0), 0) AS money_out,
                   coalesce(sum(t.amount) FILTER (WHERE t.amount >= 0), 0) AS money_in,
                   count(*) AS txn_count,
                   count(*) FILTER (WHERE t.category_id IS NULL
                                      AND (t.ai_review_reason IS NOT NULL OR t.transfer_excluded)) AS review_pending
            FROM\s""" + TxnBucketSql.FROM + """

            WHERE t.txn_date >= :from AND t.txn_date < :dayAfter
            """;
    static final String FOR_ACCOUNT = "  AND t.account_id = :accountId\n";
    static final String FOR_CATEGORY = "  AND t.category_id = :categoryId\n";
    static final String GROUPING = "GROUP BY 1, 2, 3, 4\nORDER BY 1, 2, 3, 4";

    /**
     * The txns of one month, account, category and bucket.
     *
     * @param categoryId    null for uncategorized txns
     * @param moneyOut      sum of the negative amounts (≤ 0), scale 2
     * @param moneyIn       sum of the other amounts (≥ 0), scale 2
     * @param reviewPending uncategorized txns waiting in the review queue for a category: the AI left them
     *                      ({@code ai_review_reason}), or they were marked not a transfer (docs/plans/phase-5.md §1.3)
     */
    record Cell(YearMonth month, long accountId, Long categoryId, TxnBucket bucket, BigDecimal moneyOut,
                BigDecimal moneyIn, int count, int reviewPending) {

        BigDecimal net() {
            return moneyOut.add(moneyIn);
        }

        MoneyFlow flow() {
            return MoneyFlow.of(moneyOut, moneyIn, count);
        }
    }

    private final JdbcClient jdbc;

    BucketTotalsQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every non-empty cell of the range; one account's or one category's only, if given. */
    List<Cell> find(MonthRange range, Optional<Long> accountId, Optional<Long> categoryId) {
        String sql = CELLS_SQL + (accountId.isPresent() ? FOR_ACCOUNT : "")
                + (categoryId.isPresent() ? FOR_CATEGORY : "") + GROUPING;
        JdbcClient.StatementSpec spec = jdbc.sql(sql)
                .param("from", range.firstDay())
                .param("dayAfter", range.dayAfter());
        if (accountId.isPresent()) {
            spec = spec.param("accountId", accountId.get());
        }
        if (categoryId.isPresent()) {
            spec = spec.param("categoryId", categoryId.get());
        }
        return spec.query((rs, row) -> new Cell(
                YearMonth.from(rs.getObject("month", LocalDate.class)),
                rs.getLong("account_id"),
                rs.getObject("category_id", Long.class),
                TxnBucket.valueOf(rs.getString("bucket")),
                money(rs.getBigDecimal("money_out")),
                money(rs.getBigDecimal("money_in")),
                rs.getInt("txn_count"),
                rs.getInt("review_pending"))).list();
    }

    /** {@code coalesce(…, 0)} gives an unscaled 0; amounts are NUMERIC(12,2), so anything else already has scale 2. */
    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
}
