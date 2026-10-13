package com.finalysis.transfer;

import java.time.OffsetDateTime;

/**
 * A transfer link as the API shows it (docs/plans/phase-3c.md §3).
 *
 * @param daysApart calendar days between the two sides' effective dates, either way; null when one-sided
 * @param out       the money-out side, or null for a one-sided incoming transfer
 * @param in        the money-in side, or null for a one-sided outgoing transfer
 */
public record TransferLinkResponse(long id, TransferStatus status, TransferMethod method, OffsetDateTime confirmedAt,
                                   OffsetDateTime createdAt, Long daysApart, TxnSummary out, TxnSummary in) {

    static TransferLinkResponse from(TransferLink link) {
        TxnSummary out = TxnSummary.from(link.getOutTxn());
        TxnSummary in = TxnSummary.from(link.getInTxn());
        Long daysApart = out == null || in == null ? null
                : Math.abs(in.effectiveDate().toEpochDay() - out.effectiveDate().toEpochDay());
        return new TransferLinkResponse(link.getId(), link.getStatus(), link.getMethod(), link.getConfirmedAt(),
                link.getCreatedAt(), daysApart, out, in);
    }
}
