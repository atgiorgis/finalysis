package com.finalysis.chat.citation;

import com.finalysis.chat.citation.Citation.CitedTxn;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The txns citations point at (docs/plans/phase-6.md §6): each with its account's name, its statement and the line it
 * was imported from. Read-only SQL, local only: what it returns is never sent to the model and never logged.
 */
@Component
public class CitationQuery {

    private static final String SELECT = """
            SELECT t.id, t.fingerprint, t.account_id, a.name AS account_name, t.txn_date, t.post_date, t.amount,
                   t.merchant, c.name AS category, s.id AS statement_id, s.period_start, s.period_end,
                   t.source_line_number, t.source_line_text
            FROM txn t
            JOIN account a ON a.id = t.account_id
            JOIN statement s ON s.id = t.statement_id
            LEFT JOIN category c ON c.id = t.category_id
            """;

    private final JdbcClient jdbc;

    public CitationQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Id → txn, for the ids that exist. */
    @Transactional(readOnly = true)
    public Map<Long, CitedTxn> byIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, CitedTxn> found = new LinkedHashMap<>();
        query(SELECT + "WHERE t.id IN (:keys)", ids).forEach(t -> found.put(t.id(), t));
        return found;
    }

    /** Fingerprint → txn, for the fingerprints that still exist (a stored citation, read again later). */
    @Transactional(readOnly = true)
    public Map<String, CitedTxn> byFingerprints(Collection<String> fingerprints) {
        if (fingerprints.isEmpty()) {
            return Map.of();
        }
        Map<String, CitedTxn> found = new LinkedHashMap<>();
        query(SELECT + "WHERE t.fingerprint IN (:keys)", fingerprints).forEach(t -> found.put(t.fingerprint(), t));
        return found;
    }

    private List<CitedTxn> query(String sql, Collection<?> keys) {
        return jdbc.sql(sql).param("keys", keys).query((rs, row) -> new CitedTxn(CitedTxn.Status.FOUND,
                rs.getString("fingerprint"), rs.getLong("id"), rs.getLong("account_id"), rs.getString("account_name"),
                rs.getObject("txn_date", LocalDate.class), rs.getObject("post_date", LocalDate.class),
                rs.getBigDecimal("amount"), rs.getString("merchant"), rs.getString("category"),
                new Citation.Statement(rs.getLong("statement_id"), rs.getObject("period_start", LocalDate.class),
                        rs.getObject("period_end", LocalDate.class)),
                new Citation.SourceLine(rs.getObject("source_line_number", Integer.class),
                        rs.getString("source_line_text")))).list();
    }
}
