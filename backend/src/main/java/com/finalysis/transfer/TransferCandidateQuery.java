package com.finalysis.transfer;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Finds every candidate transfer pair across all accounts (ING-05, docs/plans/phase-3c.md §2.1).
 *
 * <p>Bank-agnostic by construction: the rules use only amount, sign, account, account type,
 * {@code source_section}, the effective date and the link, rejection and exclusion state. The query
 * selects ids and dates only, never a text column, so a description can't influence detection.
 *
 * <p>Rule 7 lets the present side of an UNMATCHED link through, so detection can complete it (§2.4).
 * A pair whose two sides are both on links is never a candidate, even two UNMATCHED ones: both are
 * already transfers, and joining them would mean replacing two user decisions with one link.
 */
@Component
public class TransferCandidateQuery {

    // A txn is on at most one link (V3), so each LEFT JOIN adds at most one row.
    static final String CANDIDATES_SQL = """
            SELECT o.id AS out_id, o.account_id AS out_account_id,
                   coalesce(o.post_date, o.txn_date) AS out_date, ol.id AS out_link_id,
                   i.id AS in_id, i.account_id AS in_account_id,
                   coalesce(i.post_date, i.txn_date) AS in_date, il.id AS in_link_id
            FROM txn o
            JOIN account oa ON oa.id = o.account_id
            JOIN txn i ON i.amount = -o.amount                                    -- rule 1
                      AND i.account_id <> o.account_id                            -- rule 2
            JOIN account ia ON ia.id = i.account_id
            LEFT JOIN transfer_link ol ON ol.out_txn_id = o.id OR ol.in_txn_id = o.id
            LEFT JOIN transfer_link il ON il.out_txn_id = i.id OR il.in_txn_id = i.id
            WHERE o.amount < 0                                                    -- rule 1
              AND abs(coalesce(i.post_date, i.txn_date) - coalesce(o.post_date, o.txn_date))
                  <= :windowDays                                                  -- rule 3
              AND oa.type IN ('CHECKING', 'SAVINGS')                              -- rule 4
              AND ia.type IN ('CHECKING', 'SAVINGS', 'CREDIT_CARD')
              AND o.source_section NOT IN ('FEE', 'INTEREST')                     -- rule 5
              AND i.source_section NOT IN ('FEE', 'INTEREST')
              AND NOT o.transfer_excluded AND NOT i.transfer_excluded             -- rule 6
              AND NOT EXISTS (SELECT 1 FROM transfer_rejection r
                              WHERE r.out_txn_id = o.id AND r.in_txn_id = i.id)
              AND (ol.id IS NULL OR (ol.status = 'UNMATCHED' AND ol.out_txn_id = o.id)) -- rule 7
              AND (il.id IS NULL OR (il.status = 'UNMATCHED' AND il.in_txn_id = i.id))
              AND (ol.id IS NULL OR il.id IS NULL)
            ORDER BY o.id, i.id
            """;

    private final JdbcClient jdbc;

    public TransferCandidateQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every candidate pair, ordered by out txn id, then in txn id. */
    public List<CandidatePair> find(int windowDays) {
        if (windowDays < 0) {
            throw new IllegalArgumentException("windowDays must not be negative, was " + windowDays);
        }
        return jdbc.sql(CANDIDATES_SQL)
                .param("windowDays", windowDays)
                .query((rs, row) -> new CandidatePair(
                        new CandidatePair.Side(rs.getLong("out_id"), rs.getLong("out_account_id"),
                                rs.getObject("out_date", LocalDate.class),
                                rs.getObject("out_link_id", Long.class)),
                        new CandidatePair.Side(rs.getLong("in_id"), rs.getLong("in_account_id"),
                                rs.getObject("in_date", LocalDate.class),
                                rs.getObject("in_link_id", Long.class))))
                .list();
    }
}
