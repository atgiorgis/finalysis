-- Alert dismissals (Phase 5 step 5, docs/plans/phase-5.md §3.1, §6.2). Alerts are computed on demand and never
-- stored; only the user's dismissals are. The key is derived from txn fingerprints or a category id, never a txn id,
-- so a dismissal survives deleting and re-importing the same statement. No foreign keys for the same reason: a
-- dismissal whose alert no longer exists is harmless, and comes back into force if the same facts reappear.
-- Written only by AlertDismissalService through PUT/DELETE /api/alerts/{key}/dismissal, a user action, never a
-- chatbot tool (SEC-03).

CREATE TABLE alert_dismissal (
    alert_key    TEXT        NOT NULL,
    alert_type   TEXT        NOT NULL,
    note         TEXT,
    dismissed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_alert_dismissal PRIMARY KEY (alert_key),
    CONSTRAINT ck_alert_dismissal_key CHECK (alert_key ~ '^[a-z-]{1,40}\.[0-9]{4}-[0-9]{2}\.[0-9a-f]{16}$'),
    CONSTRAINT ck_alert_dismissal_type CHECK (alert_type IN ('CATEGORY_SPIKE', 'LARGE_TRANSACTION',
        'POSSIBLE_DUPLICATE', 'RECURRING_PRICE_INCREASE', 'NEW_RECURRING')),
    CONSTRAINT ck_alert_dismissal_note CHECK (char_length(note) BETWEEN 1 AND 200)
);

COMMENT ON TABLE alert_dismissal IS
    'Alerts the user dismissed, by stable alert key. Alerts themselves are computed on demand, never stored.';
