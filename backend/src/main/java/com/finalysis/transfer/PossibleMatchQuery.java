package com.finalysis.transfer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Finds possible matches between one-sided links (ING-07, docs/plans/phase-3c.md §4.5): an UNMATCHED
 * link with only an out side and one with only an in side whose txns are equal and opposite, in
 * different accounts, within the detection window, and not a rejected pair. No account-type or section
 * rule: the user has already said both are transfers. Read-only, for the review queue; detection never
 * joins them. Ids, account ids, dates and link times only, never a text column.
 */
@Component
public class PossibleMatchQuery {

    static final String POSSIBLE_MATCHES_SQL = """
            SELECT o.id AS out_id, o.account_id AS out_account_id, coalesce(o.post_date, o.txn_date) AS out_date,
                   ol.id AS out_link_id,
                   i.id AS in_id, i.account_id AS in_account_id, coalesce(i.post_date, i.txn_date) AS in_date,
                   il.id AS in_link_id,
                   greatest(ol.created_at, il.created_at) AS since
            FROM transfer_link ol
            JOIN txn o ON o.id = ol.out_txn_id
            JOIN txn i ON i.amount = -o.amount AND i.account_id <> o.account_id
            JOIN transfer_link il ON il.in_txn_id = i.id
            WHERE ol.status = 'UNMATCHED' AND il.status = 'UNMATCHED'
              AND o.amount < 0
              AND abs(coalesce(i.post_date, i.txn_date) - coalesce(o.post_date, o.txn_date)) <= :windowDays
              AND NOT EXISTS (SELECT 1 FROM transfer_rejection r
                              WHERE r.out_txn_id = o.id AND r.in_txn_id = i.id)
            ORDER BY ol.id, il.id
            """;

    /**
     * Two one-sided links the user could join.
     *
     * @param pair  the two txns; each side's {@code unmatchedLinkId} is its link
     * @param since when the newer of the two links was created
     */
    public record PossibleMatch(CandidatePair pair, OffsetDateTime since) {

        public PossibleMatch {
            Objects.requireNonNull(pair.out().unmatchedLinkId(), "out link");
            Objects.requireNonNull(pair.in().unmatchedLinkId(), "in link");
            Objects.requireNonNull(since, "since");
        }

        public long outLinkId() {
            return pair.out().unmatchedLinkId();
        }

        public long inLinkId() {
            return pair.in().unmatchedLinkId();
        }
    }

    private final JdbcClient jdbc;

    public PossibleMatchQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every possible match, ordered by out link id, then in link id. */
    public List<PossibleMatch> find(int windowDays) {
        if (windowDays < 0) {
            throw new IllegalArgumentException("windowDays must not be negative, was " + windowDays);
        }
        return jdbc.sql(POSSIBLE_MATCHES_SQL)
                .param("windowDays", windowDays)
                .query((rs, row) -> new PossibleMatch(TransferCandidateQuery.pair(rs),
                        rs.getObject("since", OffsetDateTime.class)))
                .list();
    }
}
