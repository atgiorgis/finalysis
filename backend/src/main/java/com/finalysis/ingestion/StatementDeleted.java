package com.finalysis.ingestion;

/**
 * Published inside a statement delete's transaction once the statement and its rows are gone.
 * Listeners that must see the result react after commit (transfer detection re-runs, so the deleted
 * rows' counterparts can match again; docs/plans/phase-3c.md §4.4). Ids only (SEC-10).
 */
public record StatementDeleted(long statementId, long accountId) {
}
