package com.finalysis.transfer;

import com.finalysis.ingestion.Txn;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.hibernate.annotations.Generated;

/**
 * Links the two sides of a transfer between the user's own accounts (ING-05, GR-04).
 *
 * <p>A MATCHED link has both sides. An UNMATCHED link has one side: the user marked it as a transfer
 * to or from an account that isn't in the app. A txn belongs to at most one link, on either side
 * (V3), and {@code txn.is_transfer} is true exactly when it is on one; a trigger keeps that in step
 * (V9). The factories build only the states of {@code docs/plans/phase-3c.md} §1.2.
 */
@Entity
@Table(name = "transfer_link")
public class TransferLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "out_txn_id")
    private Txn outTxn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "in_txn_id")
    private Txn inTxn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    /** When the user looked at the link; always set for USER links. */
    private OffsetDateTime confirmedAt;

    @Generated
    private OffsetDateTime createdAt;

    protected TransferLink() {
    }

    private TransferLink(Txn outTxn, Txn inTxn, TransferMethod method, TransferStatus status,
                         OffsetDateTime confirmedAt) {
        this.outTxn = outTxn;
        this.inTxn = inTxn;
        this.method = method;
        this.status = status;
        this.confirmedAt = confirmedAt;
    }

    /** Found by detection: MATCHED, AUTO, not yet confirmed. */
    public static TransferLink detected(Txn outTxn, Txn inTxn) {
        return new TransferLink(Objects.requireNonNull(outTxn), Objects.requireNonNull(inTxn),
                TransferMethod.AUTO, TransferStatus.MATCHED, null);
    }

    /** Linked by hand: MATCHED, USER, confirmed. */
    public static TransferLink linkedByUser(Txn outTxn, Txn inTxn, OffsetDateTime confirmedAt) {
        return new TransferLink(Objects.requireNonNull(outTxn), Objects.requireNonNull(inTxn),
                TransferMethod.USER, TransferStatus.MATCHED, Objects.requireNonNull(confirmedAt));
    }

    /** Marked by the user as one side of a transfer to or from an account not in the app. */
    public static TransferLink oneSided(Txn outTxn, Txn inTxn, OffsetDateTime confirmedAt) {
        if ((outTxn == null) == (inTxn == null)) {
            throw new IllegalArgumentException("a one-sided transfer has exactly one side");
        }
        return new TransferLink(outTxn, inTxn, TransferMethod.USER, TransferStatus.UNMATCHED,
                Objects.requireNonNull(confirmedAt));
    }

    /**
     * Completes an UNMATCHED link with the counterpart detection found (§2.4). The pairing is new and
     * unreviewed, so the link becomes AUTO and unconfirmed.
     */
    public void complete(Txn counterpart) {
        if (status != TransferStatus.UNMATCHED) {
            throw new IllegalStateException("only an UNMATCHED link can be completed");
        }
        Objects.requireNonNull(counterpart);
        if (outTxn == null) {
            outTxn = counterpart;
        } else {
            inTxn = counterpart;
        }
        status = TransferStatus.MATCHED;
        method = TransferMethod.AUTO;
        confirmedAt = null;
    }

    /** Records that the user looked at the link; keeps the first confirmation time. */
    public void confirm(OffsetDateTime at) {
        if (confirmedAt == null) {
            confirmedAt = Objects.requireNonNull(at);
        }
    }

    public Long getId() {
        return id;
    }

    public Txn getOutTxn() {
        return outTxn;
    }

    public Txn getInTxn() {
        return inTxn;
    }

    public TransferMethod getMethod() {
        return method;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public OffsetDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
