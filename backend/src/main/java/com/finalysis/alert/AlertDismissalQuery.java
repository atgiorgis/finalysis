package com.finalysis.alert;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads dismissals for {@link AlertQueryService}. Reading only: the one write is {@link AlertDismissalService}'s, and
 * no query class depends on it (docs/plans/phase-5.md §8).
 */
@Component
class AlertDismissalQuery {

    /** A stored dismissal. */
    record Dismissal(Instant dismissedAt, String note) {
    }

    private final JdbcClient jdbc;

    AlertDismissalQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The dismissals of these keys, by key. */
    Map<String, Dismissal> find(Collection<String> keys) {
        Map<String, Dismissal> dismissals = new HashMap<>();
        if (keys.isEmpty()) {
            return dismissals;
        }
        jdbc.sql("SELECT alert_key, dismissed_at, note FROM alert_dismissal WHERE alert_key IN (:keys)")
                .param("keys", keys)
                .query(rs -> {
                    Timestamp at = rs.getTimestamp("dismissed_at");
                    dismissals.put(rs.getString("alert_key"), new Dismissal(at.toInstant(), rs.getString("note")));
                });
        return dismissals;
    }
}
