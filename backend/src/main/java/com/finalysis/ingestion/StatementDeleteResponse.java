package com.finalysis.ingestion;

/**
 * Body of {@code DELETE /api/statements/{id}}.
 *
 * @param transactionsDeleted  the statement's own rows, removed with it
 * @param transferLinksRemoved links on those rows, confirmed and USER links included; their
 *                             counterparts are no longer transfers until detection or the user links them
 */
public record StatementDeleteResponse(long statementId, long transactionsDeleted, long transferLinksRemoved) {
}
