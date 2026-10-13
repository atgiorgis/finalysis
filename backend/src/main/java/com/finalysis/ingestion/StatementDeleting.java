package com.finalysis.ingestion;

/**
 * Published inside a statement delete's transaction, under the account lock and before any row is
 * deleted, so listeners can take their own locks first (transfer detection's lock,
 * docs/plans/phase-3c.md §2.5). Ids only; ingestion knows nothing about who listens.
 */
public record StatementDeleting(long statementId, long accountId) {
}
