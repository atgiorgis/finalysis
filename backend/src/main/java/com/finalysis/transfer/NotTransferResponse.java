package com.finalysis.transfer;

/**
 * Body of {@code POST /api/transactions/{id}/not-transfer}.
 *
 * @param removedLinkId the link the txn was on, now deleted; null when it was on none
 */
public record NotTransferResponse(long txnId, boolean transferExcluded, Long removedLinkId) {
}
