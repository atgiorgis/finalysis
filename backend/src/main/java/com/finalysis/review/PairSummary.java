package com.finalysis.review;

import com.finalysis.transfer.CandidatePair;

/** A candidate pair as the queue shows it: the two txn ids and the days between their effective dates. */
public record PairSummary(long outTxnId, long inTxnId, long daysApart) {

    static PairSummary from(CandidatePair pair) {
        return new PairSummary(pair.out().txnId(), pair.in().txnId(), pair.daysApart());
    }
}
