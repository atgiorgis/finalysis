package com.finalysis.ingestion;

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
import org.hibernate.annotations.Generated;

/**
 * Links the two sides of a transfer between the user's own accounts (ING-05, GR-04).
 *
 * <p>{@code inTxn} is null while UNMATCHED, e.g. a card payment whose card statement isn't imported yet.
 * A txn belongs to at most one link, on either side; the database enforces this (V3).
 */
@Entity
@Table(name = "transfer_link")
public class TransferLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "out_txn_id", nullable = false)
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

    @Generated
    private OffsetDateTime createdAt;

    protected TransferLink() {
    }

    public TransferLink(Txn outTxn, Txn inTxn, TransferMethod method, TransferStatus status) {
        this.outTxn = outTxn;
        this.inTxn = inTxn;
        this.method = method;
        this.status = status;
    }

    /** Attaches the in side, e.g. once the card statement is imported. */
    public void match(Txn inTxn) {
        this.inTxn = inTxn;
        this.status = TransferStatus.MATCHED;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
