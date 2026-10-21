package com.finalysis.chat.tool;

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
     * A total: a label for the citation and the drill-down parameters of {@code GET /api/analytics/transactions} that
     * list its rows (one set per side for a comparison).
     */
    public record Cell(String label, List<Map<String, String>> drillDowns) implements Target {
    }

    /** An alert, by its key. */
    public record Alert(String key) implements Target {
    }

    /** A recurring series: account and stored merchant. */
    public record Series(long accountId, String merchant) implements Target {
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
}
