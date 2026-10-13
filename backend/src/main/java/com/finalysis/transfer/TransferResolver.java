package com.finalysis.transfer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Chooses among candidate pairs (ING-05, docs/plans/phase-3c.md §2.3 and §2.4). Pure: no database,
 * no clock, and the same answer for the same pairs in any order.
 *
 * <p>The pairs form a graph of out and in txns; each connected group is resolved on its own:
 * <ol>
 *   <li>one out and one in: linked, or, when one side is on an UNMATCHED link, that link is completed;</li>
 *   <li>k outs on one account and k ins on one account: the i-th earliest out with the i-th earliest in
 *       (ties by txn id), if every such pair is itself a candidate;</li>
 *   <li>anything else: no link; the group waits for the user.</li>
 * </ol>
 * A group of more than one pair that contains the present side of an UNMATCHED link goes to review
 * too: only a group that resolves to that single pair completes the link the user created (§2.4).
 */
@Component
public class TransferResolver {

    private static final Comparator<CandidatePair> PAIR_ORDER = Comparator
            .comparingLong((CandidatePair pair) -> pair.out().txnId())
            .thenComparingLong(pair -> pair.in().txnId());

    private static final Comparator<CandidatePair.Side> DATE_ORDER = Comparator
            .comparing(CandidatePair.Side::effectiveDate)
            .thenComparingLong(CandidatePair.Side::txnId);

    /**
     * What detection should write, and what it leaves for the user.
     *
     * @param newLinks    pairs to link as MATCHED, AUTO, unconfirmed
     * @param completions UNMATCHED links to complete with their counterpart
     * @param forReview   groups left unresolved, ordered by their smallest txn id
     */
    public record Resolution(List<CandidatePair> newLinks, List<Completion> completions,
                             List<CandidateGroup> forReview) {

        public Resolution {
            newLinks = List.copyOf(newLinks);
            completions = List.copyOf(completions);
            forReview = List.copyOf(forReview);
        }
    }

    /** Completes UNMATCHED link {@code linkId} with the txn on its missing side. */
    public record Completion(long linkId, long counterpartTxnId) {
    }

    public Resolution resolve(Collection<CandidatePair> candidates) {
        List<CandidatePair> pairs = candidates.stream().distinct().sorted(PAIR_ORDER).toList();

        UnionFind groups = new UnionFind();
        for (CandidatePair pair : pairs) {
            groups.union(pair.out().txnId(), pair.in().txnId());
        }
        Map<Long, List<CandidatePair>> byGroup = new LinkedHashMap<>();
        for (CandidatePair pair : pairs) {
            byGroup.computeIfAbsent(groups.find(pair.out().txnId()), root -> new ArrayList<>()).add(pair);
        }
        List<CandidateGroup> ordered = byGroup.values().stream()
                .map(CandidateGroup::new)
                .sorted(Comparator.comparingLong(group -> group.txnIds().first()))
                .toList();

        List<CandidatePair> newLinks = new ArrayList<>();
        List<Completion> completions = new ArrayList<>();
        List<CandidateGroup> forReview = new ArrayList<>();
        for (CandidateGroup group : ordered) {
            resolveGroup(group, newLinks, completions, forReview);
        }
        newLinks.sort(PAIR_ORDER);
        completions.sort(Comparator.comparingLong(Completion::linkId));
        return new Resolution(newLinks, completions, forReview);
    }

    private static void resolveGroup(CandidateGroup group, List<CandidatePair> newLinks,
                                     List<Completion> completions, List<CandidateGroup> forReview) {
        List<CandidatePair> pairs = group.pairs();
        if (pairs.size() == 1) {
            CandidatePair pair = pairs.getFirst();
            if (pair.out().unmatchedLinkId() != null) {
                completions.add(new Completion(pair.out().unmatchedLinkId(), pair.in().txnId()));
            } else if (pair.in().unmatchedLinkId() != null) {
                completions.add(new Completion(pair.in().unmatchedLinkId(), pair.out().txnId()));
            } else {
                newLinks.add(pair);
            }
            return;
        }
        List<CandidatePair> inDateOrder = pairedInDateOrder(pairs);
        if (inDateOrder == null) {
            forReview.add(group);
        } else {
            newLinks.addAll(inDateOrder);
        }
    }

    /** Rule 2, or null when the group doesn't qualify for it. */
    private static List<CandidatePair> pairedInDateOrder(List<CandidatePair> pairs) {
        Map<Long, CandidatePair.Side> outs = new HashMap<>();
        Map<Long, CandidatePair.Side> ins = new HashMap<>();
        Set<List<Long>> candidates = new HashSet<>();
        for (CandidatePair pair : pairs) {
            outs.put(pair.out().txnId(), pair.out());
            ins.put(pair.in().txnId(), pair.in());
            candidates.add(List.of(pair.out().txnId(), pair.in().txnId()));
        }
        if (outs.size() != ins.size()
                || accounts(outs.values()) != 1 || accounts(ins.values()) != 1
                || anyUnmatched(outs.values()) || anyUnmatched(ins.values())) {
            return null;
        }
        List<CandidatePair.Side> sortedOuts = outs.values().stream().sorted(DATE_ORDER).toList();
        List<CandidatePair.Side> sortedIns = ins.values().stream().sorted(DATE_ORDER).toList();
        List<CandidatePair> result = new ArrayList<>();
        for (int i = 0; i < sortedOuts.size(); i++) {
            CandidatePair.Side out = sortedOuts.get(i);
            CandidatePair.Side in = sortedIns.get(i);
            if (!candidates.contains(List.of(out.txnId(), in.txnId()))) {
                return null;
            }
            result.add(new CandidatePair(out, in));
        }
        return result;
    }

    private static long accounts(Collection<CandidatePair.Side> sides) {
        return sides.stream().mapToLong(CandidatePair.Side::accountId).distinct().count();
    }

    private static boolean anyUnmatched(Collection<CandidatePair.Side> sides) {
        return sides.stream().map(CandidatePair.Side::unmatchedLinkId).anyMatch(Objects::nonNull);
    }

    /** Disjoint sets of txn ids. Out and in txns never share an id, so one id space holds both. */
    private static final class UnionFind {

        private final Map<Long, Long> parent = new HashMap<>();

        long find(long id) {
            long root = id;
            while (parent.getOrDefault(root, root) != root) {
                root = parent.get(root);
            }
            // Path compression.
            long current = id;
            while (current != root) {
                long next = parent.get(current);
                parent.put(current, root);
                current = next;
            }
            return root;
        }

        void union(long a, long b) {
            long rootA = find(a);
            long rootB = find(b);
            if (rootA != rootB) {
                // The smaller id becomes the root, so roots don't depend on input order.
                parent.put(Math.max(rootA, rootB), Math.min(rootA, rootB));
            }
        }
    }
}
