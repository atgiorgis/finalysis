package com.finalysis.transfer;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Finds competitors for early links (ING-07, docs/plans/phase-3c.md §4.6): a txn that would form a
 * candidate pair with a side of an unconfirmed AUTO link if that link didn't exist. Read-only, for the
 * review queue; detection never changes a link (§2.5).
 *
 * <p>Rules 1–6 are {@link TransferCandidateQuery}'s own. Rule 7 is waived for the link's side only:
 * the competitor must still be on no link, or be the present side of an UNMATCHED link on its own
 * side. The link's own pair never appears, since its other side is on a MATCHED link. Ids, account ids
 * and dates only, never a text column.
 */
@Component
public class CompetingCandidateQuery {

    static final String COMPETING_SQL = "SELECT " + TransferCandidateQuery.PAIR_COLUMNS + """
            , CASE WHEN ol.status = 'MATCHED' THEN ol.id ELSE il.id END AS link_id
            """ + TransferCandidateQuery.RULES_1_TO_6 + """
              AND (   (ol.status = 'MATCHED' AND ol.method = 'AUTO' AND ol.confirmed_at IS NULL
                       AND ol.out_txn_id = o.id                                     -- o is on the early link
                       AND (il.id IS NULL OR (il.status = 'UNMATCHED' AND il.in_txn_id = i.id)))
                   OR (il.status = 'MATCHED' AND il.method = 'AUTO' AND il.confirmed_at IS NULL
                       AND il.in_txn_id = i.id                                      -- i is on the early link
                       AND (ol.id IS NULL OR (ol.status = 'UNMATCHED' AND ol.out_txn_id = o.id))))
            ORDER BY link_id, o.id, i.id
            """;

    /**
     * A candidate pair that competes with an early link.
     *
     * @param linkId the unconfirmed AUTO link one side of {@code pair} is on
     * @param pair   that side and its competitor; the side on the link has no link id, as if the link
     *               didn't exist
     */
    public record CompetingPair(long linkId, CandidatePair pair) {
    }

    private final JdbcClient jdbc;

    public CompetingCandidateQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every competing pair, ordered by link id, then out txn id, then in txn id. */
    public List<CompetingPair> find(int windowDays) {
        if (windowDays < 0) {
            throw new IllegalArgumentException("windowDays must not be negative, was " + windowDays);
        }
        return jdbc.sql(COMPETING_SQL)
                .param("windowDays", windowDays)
                .query((rs, row) -> new CompetingPair(rs.getLong("link_id"), TransferCandidateQuery.pair(rs)))
                .list();
    }
}
