package com.finalysis.transfer;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    /**
     * Rules 1–6 of §2.1 over every out txn {@code o} and in txn {@code i}, with each one's link (if any)
     * as {@code ol} and {@code il}; rule 7 is left to the caller. Shared with {@link CompetingCandidateQuery},
     * which waives rule 7 for one link's own txns, so the two can't drift apart. Takes {@code :windowDays}.
     */
    // A txn is on at most one link (V3), so each LEFT JOIN adds at most one row.
    static final String RULES_1_TO_6 = """
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
            """;

    /** The two sides of a pair, as {@link #side} reads them; a link id only for an UNMATCHED link. */
    static final String PAIR_COLUMNS = """
            o.id AS out_id, o.account_id AS out_account_id, coalesce(o.post_date, o.txn_date) AS out_date,
            CASE WHEN ol.status = 'UNMATCHED' THEN ol.id END AS out_link_id,
            i.id AS in_id, i.account_id AS in_account_id, coalesce(i.post_date, i.txn_date) AS in_date,
            CASE WHEN il.status = 'UNMATCHED' THEN il.id END AS in_link_id
            """;

    static final String CANDIDATES_SQL = "SELECT " + PAIR_COLUMNS + RULES_1_TO_6 + """
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
                .query((rs, row) -> pair(rs))
                .list();
    }

    /** The pair in {@link #PAIR_COLUMNS}' columns. */
    static CandidatePair pair(ResultSet rs) throws SQLException {
        return new CandidatePair(side(rs, "out"), side(rs, "in"));
    }

    /** One side, from the {@code <prefix>_id}, {@code _account_id}, {@code _date} and {@code _link_id} columns. */
    static CandidatePair.Side side(ResultSet rs, String prefix) throws SQLException {
        return new CandidatePair.Side(rs.getLong(prefix + "_id"), rs.getLong(prefix + "_account_id"),
                rs.getObject(prefix + "_date", LocalDate.class), rs.getObject(prefix + "_link_id", Long.class));
    }
}
