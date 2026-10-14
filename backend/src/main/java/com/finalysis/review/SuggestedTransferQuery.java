package com.finalysis.review;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Finds suggested one-sided transfers (CAT-08, docs/plans/phase-4.md §8): txns in a Transfer-kind category
 * that are on no transfer link and that the user hasn't marked not a transfer. Plain SQL on {@code category.kind},
 * so the review queue depends on no categorization class. Read-only; the caller leaves out txns that are in a
 * candidate group or competing for a link, which the queue lists there instead. Ids, sources and times only,
 * never a text column.
 */
@Component
public class SuggestedTransferQuery {

    static final String SUGGESTED_SQL = """
            SELECT t.id, t.category_source, t.category_rule_id, coalesce(t.categorized_at, t.created_at) AS since
            FROM txn t
            JOIN category c ON c.id = t.category_id
            WHERE c.kind = 'TRANSFER'
              AND NOT t.is_transfer
              AND NOT t.transfer_excluded
            ORDER BY t.id
            """;

    /**
     * One txn the categorization says is a transfer while no link says so.
     *
     * @param categorySource {@code RULE}, {@code AI} or {@code USER}
     * @param ruleId         the rule that set a {@code RULE} category, else null
     * @param since          when it was categorized ({@code created_at} if that time is missing)
     */
    public record Suggested(long txnId, String categorySource, Long ruleId, OffsetDateTime since) {
    }

    private final JdbcClient jdbc;

    public SuggestedTransferQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every suggested one-sided transfer, ordered by txn id. */
    public List<Suggested> find() {
        return jdbc.sql(SUGGESTED_SQL)
                .query((rs, row) -> new Suggested(rs.getLong("id"), rs.getString("category_source"),
                        rs.getObject("category_rule_id", Long.class), rs.getObject("since", OffsetDateTime.class)))
                .list();
    }
}
