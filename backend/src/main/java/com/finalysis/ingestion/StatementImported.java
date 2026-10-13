package com.finalysis.ingestion;

/**
 * Published inside an import's transaction once its statement and rows are saved. Listeners that
 * must see the rows react after commit (transfer detection, docs/plans/phase-3c.md §2.5). Ids only,
 * so it is safe to log (SEC-10); ingestion knows nothing about who listens.
 */
public record StatementImported(long statementId, long accountId) {
}
