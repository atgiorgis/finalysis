package com.finalysis.transfer;

/**
 * Body of {@code POST /api/transfers}: both sides link by hand (MATCHED, USER); one side marks a
 * one-sided transfer (UNMATCHED, USER). Neither side is a 422 {@code transfer-invalid}.
 */
public record CreateTransferRequest(Long outTxnId, Long inTxnId) {
}
