package com.finalysis.analytics;

import com.finalysis.analytics.CoverageResponse.DateSpan;
import com.finalysis.analytics.CoverageResponse.FlaggedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Statement periods and balance checks, read in plain SQL from {@code statement} (docs/plans/phase-5.md §1.6). Ids,
 * dates and outcomes only, never a file name or text column.
 */
@Component
class CoverageQuery {

    /** Each account's covered days: its statement periods merged by Postgres's {@code range_agg}, oldest first. */
    static final String SPANS_SQL = """
            SELECT account_id, lower(span) AS first_day, upper(span) - 1 AS last_day
            FROM (SELECT s.account_id, unnest(range_agg(daterange(s.period_start, s.period_end, '[]'))) AS span
                  FROM statement s
                  GROUP BY s.account_id) spans
            ORDER BY account_id, first_day
            """;

    /** Statements whose balance check failed or couldn't run. */
    static final String FLAGGED_SQL = """
            SELECT s.id, s.account_id, s.period_start, s.period_end, s.balance_check, s.reviewed_at
            FROM statement s
            WHERE s.balance_check IN ('FAILED', 'UNVERIFIED')
            """;
    static final String IN_RANGE = "  AND s.period_start < :dayAfter AND s.period_end >= :from\n";
    static final String FOR_ACCOUNT = "  AND s.account_id = :accountId\n";

    /** A flagged statement and its account. */
    record Flagged(long accountId, FlaggedStatement statement) {
    }

    private final JdbcClient jdbc;

    CoverageQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    CoverageMap load() {
        Map<Long, List<DateSpan>> spans = new LinkedHashMap<>();
        jdbc.sql(SPANS_SQL).query(rs -> {
            spans.computeIfAbsent(rs.getLong("account_id"), id -> new ArrayList<>()).add(
                    new DateSpan(rs.getObject("first_day", LocalDate.class), rs.getObject("last_day", LocalDate.class)));
        });
        return new CoverageMap(spans);
    }

    /**
     * Flagged statements, oldest period first: those overlapping the range if one is given (all otherwise), and one
     * account's only if given.
     */
    List<Flagged> flagged(Optional<MonthRange> range, Optional<Long> accountId) {
        String sql = FLAGGED_SQL + (range.isPresent() ? IN_RANGE : "") + (accountId.isPresent() ? FOR_ACCOUNT : "")
                + "ORDER BY s.period_start, s.id";
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        if (range.isPresent()) {
            spec = spec.param("from", range.get().firstDay()).param("dayAfter", range.get().dayAfter());
        }
        if (accountId.isPresent()) {
            spec = spec.param("accountId", accountId.get());
        }
        return spec.query((rs, row) -> {
            Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
            return new Flagged(rs.getLong("account_id"), new FlaggedStatement(rs.getLong("id"),
                    rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
                    "FAILED".equals(rs.getString("balance_check"))
                            ? MonthFlagType.RECONCILIATION_FAILED : MonthFlagType.BALANCE_UNVERIFIED,
                    reviewedAt == null ? null : reviewedAt.toInstant()));
        }).list();
    }
}
