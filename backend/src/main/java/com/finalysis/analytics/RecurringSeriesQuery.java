package com.finalysis.analytics;

import com.finalysis.analytics.MonthlySeries.Charge;
import com.finalysis.analytics.MonthlySeries.Run;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The charges the recurrence detector reads (docs/plans/phase-5.md §6.4), and its runs: money out with a cleaned
 * merchant, outside both transfer buckets ({@link TxnBucketSql#BUCKET}), as positive magnitudes. Never a raw
 * description. Public for the recurring alerts (step 6); it runs in the caller's read-only transaction.
 */
@Component
public class RecurringSeriesQuery {

    static final String SQL = "SELECT b.id, b.account_id, b.merchant, b.txn_date, -b.amount AS amount, b.category_id,"
            + " b.fingerprint\nFROM (SELECT t.id, t.account_id, t.merchant, t.txn_date, t.amount, t.category_id,"
            + " t.fingerprint, " + TxnBucketSql.BUCKET + " AS bucket\n      FROM " + TxnBucketSql.FROM + """

                  WHERE t.amount < 0 AND t.merchant IS NOT NULL) b
            WHERE b.bucket NOT IN ('LINKED_TRANSFER', 'UNLINKED_TRANSFER')
            """;
    static final String WINDOW = "  AND b.txn_date >= :since AND b.txn_date < :dayAfter\n";

    private final JdbcClient jdbc;
    private final MonthlySeries series;

    RecurringSeriesQuery(JdbcClient jdbc, AnalyticsProperties properties) {
        this.jdbc = jdbc;
        this.series = new MonthlySeries(properties.recurring().minGapDays(), properties.recurring().maxGapDays());
    }

    /**
     * The runs of the charges dated from {@code since} to the day before {@code dayAfter}, single charges included:
     * the runs as they stood on that day (runs are prefix-stable, see {@link MonthlySeries}), read over a bounded
     * window so a check's cost follows its range, not the history.
     *
     * <p>A run that began before {@code since} is cut: only its charges from {@code since} are read. A run whose first
     * charge read is at least {@code max-gap-days} after {@code since} is whole, since a monthly predecessor would have
     * been read. Callers choose {@code since} so the charges they judge, and those they compare them with, are inside.
     */
    public List<Run> runs(LocalDate since, LocalDate dayAfter) {
        return series.runs(jdbc.sql(SQL + WINDOW).param("since", since).param("dayAfter", dayAfter)
                .query((rs, row) -> charge(rs)).list());
    }

    /** Each account and merchant's latest run over the whole history (§6.4, §6.5). */
    List<Run> latestRuns() {
        return series.latestRuns(jdbc.sql(SQL).query((rs, row) -> charge(rs)).list());
    }

    private static Charge charge(ResultSet rs) throws SQLException {
        return new Charge(rs.getLong("id"), rs.getLong("account_id"), rs.getString("merchant"),
                rs.getObject("txn_date", LocalDate.class), rs.getBigDecimal("amount"),
                rs.getObject("category_id", Long.class), rs.getString("fingerprint"));
    }
}
