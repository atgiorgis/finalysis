package com.finalysis.transfer;

/**
 * A txn the action would put on a link is already on one (V3: at most one link per txn), or a link
 * the action names is no longer in the state it needs. Mapped to 409 {@code transfer-conflict} with the
 * link's id; the user unlinks first, nothing is replaced silently (docs/plans/phase-3c.md §3, §4.5).
 */
public class TransferConflictException extends RuntimeException {

    private final long linkId;

    public TransferConflictException(long txnId, long linkId) {
        this("Transaction " + txnId + " is already on transfer link " + linkId, linkId);
    }

    /** For an action on link {@code linkId} that no longer fits it, e.g. joining a link that isn't one-sided. */
    public TransferConflictException(String message, long linkId) {
        super(message);
        this.linkId = linkId;
    }

    public long getLinkId() {
        return linkId;
    }
}
