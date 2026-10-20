package com.finalysis.alert;

import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one write in analytics and alerts (docs/plans/phase-5.md §6.2, §8): dismissing an alert and undoing it, a user
 * action over {@code PUT}/{@code DELETE /api/alerts/{key}/dismissal}. <strong>Never a chatbot tool</strong> (SEC-03):
 * Phase 6's tools adapt the query services only, and no query service depends on this class (an ArchUnit rule).
 */
@Service
public class AlertDismissalService {

    /** The longest note, V20's {@code ck_alert_dismissal_note}. */
    public static final int MAX_NOTE = 200;

    private final AlertQueryService alerts;
    private final JdbcClient jdbc;

    AlertDismissalService(AlertQueryService alerts, JdbcClient jdbc) {
        this.alerts = alerts;
        this.jdbc = jdbc;
    }

    /**
     * Dismisses the current alert with this key. Idempotent: dismissing again keeps the first {@code dismissedAt} and
     * replaces the note.
     *
     * @param note optional, 1 to {@value #MAX_NOTE} characters, not blank
     * @return the alert, dismissed
     * @throws InvalidRequestException for a malformed key or note
     * @throws com.finalysis.api.NotFoundException when no current alert has that key, so arbitrary keys can't be stored
     */
    @Transactional
    public Alert dismiss(String key, String note) {
        AlertKey.parse(key);
        if (note != null && (note.isBlank() || note.length() > MAX_NOTE)) {
            throw new InvalidRequestException(List.of(new InvalidField("note",
                    "must be 1 to " + MAX_NOTE + " characters and not blank")));
        }
        Alert alert = alerts.alert(key);
        jdbc.sql("""
                        INSERT INTO alert_dismissal (alert_key, alert_type, note) VALUES (:key, :type, :note)
                        ON CONFLICT (alert_key) DO UPDATE SET note = excluded.note
                        """)
                .param("key", key)
                .param("type", alert.type().name())
                .param("note", note)
                .update();
        return alerts.alert(key);
    }

    /**
     * Undoes a dismissal. Idempotent, and needs no current alert: a dismissal whose facts are gone can still be removed.
     *
     * @throws InvalidRequestException for a malformed key
     */
    @Transactional
    public void undismiss(String key) {
        AlertKey.parse(key);
        jdbc.sql("DELETE FROM alert_dismissal WHERE alert_key = :key").param("key", key).update();
    }
}
