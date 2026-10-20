package com.finalysis.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The spending rows of a range per cleaned merchant and category (ANA-01, docs/plans/phase-5.md §5.2): the bucket is
 * {@link TxnBucketSql#BUCKET}, and spending is {@link SpendingRowsSql#SPENDING}, so a merchant list adds up to the
 * month totals' {@code spending}. SQL sums and counts; Java ranks and picks each merchant's category.
 */
@Component
class MerchantTotalsQuery {

    static final String INNER_SQL = "SELECT t.merchant, t.category_id, t.amount, t.txn_date, " + TxnBucketSql.BUCKET
            + " AS bucket\nFROM " + TxnBucketSql.FROM + "\nWHERE t.txn_date >= :from AND t.txn_date < :dayAfter\n";
    static final String FOR_ACCOUNT = "  AND t.account_id = :accountId\n";
    static final String FOR_CATEGORY = "  AND t.category_id = :categoryId\n";

    /**
     * One merchant's spending rows in one category.
     *
     * @param merchant  the cleaned merchant; null for rows without one
     * @param net       the rows' sum, signed as stored (≤ 0 when money went out)
     * @param lastDate  the latest {@code txn_date}, which breaks a tie for "the category of most rows"
     */
    record Cell(String merchant, Long categoryId, BigDecimal net, int count, LocalDate firstDate, LocalDate lastDate) {
    }

    private final JdbcClient jdbc;

    MerchantTotalsQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Cell> find(MonthRange range, Optional<Long> accountId, Optional<Long> categoryId) {
        String sql = "SELECT b.merchant, b.category_id, sum(b.amount) AS net, count(*) AS n, "
                + "min(b.txn_date) AS first_date, max(b.txn_date) AS last_date\nFROM (" + INNER_SQL
                + (accountId.isPresent() ? FOR_ACCOUNT : "") + (categoryId.isPresent() ? FOR_CATEGORY : "")
                + ") b\nWHERE " + SpendingRowsSql.SPENDING + "\nGROUP BY b.merchant, b.category_id";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("from", range.firstDay()).param("dayAfter", range.dayAfter());
        if (accountId.isPresent()) {
            spec = spec.param("accountId", accountId.get());
        }
        if (categoryId.isPresent()) {
            spec = spec.param("categoryId", categoryId.get());
        }
        return spec.query((rs, row) -> new Cell(rs.getString("merchant"), rs.getObject("category_id", Long.class),
                rs.getBigDecimal("net"), rs.getInt("n"), rs.getObject("first_date", LocalDate.class),
                rs.getObject("last_date", LocalDate.class))).list();
    }
}
