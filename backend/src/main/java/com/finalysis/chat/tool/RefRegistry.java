package com.finalysis.chat.tool;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The turn's refs (docs/plans/phase-6.md §3.1): per-turn keys for every row the model could cite, as Phase 4's per-batch
 * keys. {@code t1…} transactions, {@code c1…} total cells, {@code a1…} alerts, {@code s1…} recurring series. The same
 * target always gets the same ref within a turn. Ids, keys and drill-down parameters never leave the machine; citations
 * (§6) resolve refs back here.
 */
public final class RefRegistry {

    /** What a ref points at. */
    public sealed interface Target permits Txn, Cell, Alert, Series {
    }

    /** A transaction, by id. */
    public record Txn(long txnId) implements Target {
    }

    /**
     * A total: a label for the citation, the drill-down parameters of {@code GET /api/analytics/transactions} that
     * list its rows (one set per side for a comparison), and what each of its figures rests on, by the figure's path in
     * the cell's outbound object ({@code spending}, {@code uncategorized.moneyOut}, {@code months[2026-08].net},
     * {@code trailing3.amount}).
     */
    public record Cell(String label, List<Map<String, String>> drillDowns, Map<String, FigureBasis> figures)
            implements Target {

        public Cell {
            drillDowns = List.copyOf(drillDowns);
            figures = Map.copyOf(figures);
        }
    }

    /** An alert: its key, type and month, and the ids of the txns it sent (at most 5). */
    public record Alert(String key, String type, YearMonth month, List<Long> txnIds) implements Target {

        public Alert {
            txnIds = List.copyOf(txnIds);
        }
    }

    /** A recurring series: account, stored merchant, and the months of its first and last charge. */
    public record Series(long accountId, String merchant, YearMonth from, YearMonth to) implements Target {
    }

    private final Map<Target, String> refs = new LinkedHashMap<>();
    private final Map<String, Target> targets = new LinkedHashMap<>();
    private final Map<Character, Integer> counters = new LinkedHashMap<>();

    public String ref(Target target) {
        return refs.computeIfAbsent(target, t -> {
            char prefix = switch (t) {
                case Txn ignored -> 't';
                case Cell ignored -> 'c';
                case Alert ignored -> 'a';
                case Series ignored -> 's';
            };
            String ref = prefix + Integer.toString(counters.merge(prefix, 1, Integer::sum));
            targets.put(ref, t);
            return ref;
        });
    }

    public Optional<Target> target(String ref) {
        return Optional.ofNullable(targets.get(ref));
    }

    public boolean issued(String ref) {
        return targets.containsKey(ref);
    }

    public int size() {
        return targets.size();
    }

    /** Every ref issued so far, in issue order. */
    public List<String> issuedRefs() {
        return List.copyOf(targets.keySet());
    }
}
