package com.finalysis.transfer;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A connected group of candidate pairs that detection can't resolve on its own (docs/plans/phase-3c.md
 * §2.3 rule 3). Its txns stay {@code is_transfer = false} until the user decides; the review queue
 * lists it. Never stored: recomputed from the candidates whenever it's needed.
 *
 * @param pairs ordered by out txn id, then in txn id
 */
public record CandidateGroup(List<CandidatePair> pairs) {

    public CandidateGroup {
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("a candidate group has at least one pair");
        }
        pairs = List.copyOf(pairs);
    }

    /** Every txn in the group, ascending. */
    public SortedSet<Long> txnIds() {
        SortedSet<Long> ids = new TreeSet<>();
        for (CandidatePair pair : pairs) {
            ids.add(pair.out().txnId());
            ids.add(pair.in().txnId());
        }
        return ids;
    }
}
