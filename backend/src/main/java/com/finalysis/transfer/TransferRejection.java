package com.finalysis.transfer;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.Generated;

/**
 * A pair the user unlinked (ING-05): detection never proposes this exact out/in pair again, though
 * either txn can still match something else. Removed with either txn ({@code ON DELETE CASCADE}, V9).
 */
@Entity
@Table(name = "transfer_rejection")
public class TransferRejection {

    @EmbeddedId
    private Pair pair;

    @Generated
    private OffsetDateTime createdAt;

    protected TransferRejection() {
    }

    public TransferRejection(long outTxnId, long inTxnId) {
        this.pair = new Pair(outTxnId, inTxnId);
    }

    public Pair getPair() {
        return pair;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /** The primary key: txn ids, so detection's native SQL and this entity agree without loading txns. */
    @Embeddable
    public record Pair(
            @Column(name = "out_txn_id", nullable = false) Long outTxnId,
            @Column(name = "in_txn_id", nullable = false) Long inTxnId) {
    }
}
