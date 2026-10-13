package com.finalysis.transfer;

/**
 * A txn the action would put on a link is already on one (V3: at most one link per txn). Mapped to
 * 409 {@code transfer-conflict} with the link's id; the user unlinks first, nothing is replaced
 * silently (docs/plans/phase-3c.md §3).
 */
public class TransferConflictException extends RuntimeException {

    private final long linkId;

    public TransferConflictException(long txnId, long linkId) {
        super("Transaction " + txnId + " is already on transfer link " + linkId);
        this.linkId = linkId;
    }

    public long getLinkId() {
        return linkId;
    }
}
