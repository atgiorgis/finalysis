package com.finalysis.review;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Finds txns the user marked not a transfer that have no category (docs/plans/phase-4.md, step 9d notes). Neither the
 * Transfer-kind rules nor the AI categorize them, since only the user knows where the money went, so they wait here
 * for the user's category. Plain SQL on {@code txn.transfer_excluded}, so the review queue depends on no
 * categorization class. Read-only; ids and times only, never a text column.
 */
@Component
public class NotTransferUncategorizedQuery {

    // transfer_excluded implies NOT is_transfer (ck_txn_transfer_excluded, V9).
    static final String WAITING_SQL = """
            SELECT t.id, t.created_at
            FROM txn t
            WHERE t.transfer_excluded
              AND t.category_id IS NULL
            ORDER BY t.id
            """;

    /**
     * One txn waiting for a category.
     *
     * @param since when the txn was imported: the time it was marked is not stored
     */
    public record Waiting(long txnId, OffsetDateTime since) {
    }

    private final JdbcClient jdbc;

    public NotTransferUncategorizedQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every uncategorized txn marked not a transfer, ordered by txn id. */
    public List<Waiting> find() {
        return jdbc.sql(WAITING_SQL)
                .query((rs, row) -> new Waiting(rs.getLong("id"), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
