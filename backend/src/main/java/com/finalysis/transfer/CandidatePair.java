package com.finalysis.transfer;

import java.time.LocalDate;
import java.util.Objects;

/**
 * An out txn and an in txn that pass every rule of docs/plans/phase-3c.md §2.1. Ids, account ids and
 * effective dates only: detection never sees a description or any other text column.
 */
public record CandidatePair(Side out, Side in) {

    public CandidatePair {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(in, "in");
    }

    /** Calendar days between the two effective dates, either way. */
    public long daysApart() {
        return Math.abs(in.effectiveDate().toEpochDay() - out.effectiveDate().toEpochDay());
    }

    /**
     * One side of a candidate pair.
     *
     * @param effectiveDate    {@code coalesce(post_date, txn_date)}
     * @param unmatchedLinkId  the UNMATCHED link this txn is the present side of, or null when the txn
     *                         is on no link (§2.4)
     */
    public record Side(long txnId, long accountId, LocalDate effectiveDate, Long unmatchedLinkId) {

        public Side {
            Objects.requireNonNull(effectiveDate, "effectiveDate");
        }
    }
}
