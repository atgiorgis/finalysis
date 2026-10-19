package com.finalysis.review;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Finds txns the AI left uncategorized for the user (CAT-02, docs/plans/phase-4.md step 7 notes): its answer was below
 * the confidence threshold, or invalid twice. Plain SQL on {@code txn.ai_review_reason}, so the review queue depends
 * on no categorization class. A category of any source clears the reason, so a listed txn is always uncategorized;
 * linked txns and txns marked not a transfer are left out, as the AI leaves them out (a txn marked not a transfer is
 * listed as {@code NOT_TRANSFER_UNCATEGORIZED} instead). Read-only; ids, reasons and times only, never a text column.
 */
@Component
public class AiCategoryReviewQuery {

    static final String WAITING_SQL = """
            SELECT t.id, t.ai_review_reason, t.ai_review_at
            FROM txn t
            WHERE t.ai_review_reason IS NOT NULL
              AND t.category_id IS NULL
              AND NOT t.is_transfer
              AND NOT t.transfer_excluded
            ORDER BY t.id
            """;

    /**
     * One txn waiting for a category.
     *
     * @param reason {@code LOW_CONFIDENCE} or {@code INVALID_ANSWER}
     * @param since  when the AI left it
     */
    public record Waiting(long txnId, String reason, OffsetDateTime since) {
    }

    private final JdbcClient jdbc;

    public AiCategoryReviewQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every txn the AI left for review, ordered by txn id. */
    public List<Waiting> find() {
        return jdbc.sql(WAITING_SQL)
                .query((rs, row) -> new Waiting(rs.getLong("id"), rs.getString("ai_review_reason"),
                        rs.getObject("ai_review_at", OffsetDateTime.class)))
                .list();
    }
}
