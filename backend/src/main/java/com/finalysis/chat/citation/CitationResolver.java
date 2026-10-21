package com.finalysis.chat.citation;

import com.finalysis.analytics.DrillDownFilter;
import com.finalysis.analytics.DrillDownResponse;
import com.finalysis.analytics.DrillDownService;
import com.finalysis.analytics.TrendMeasure;
import com.finalysis.analytics.TxnBucket;
import com.finalysis.chat.citation.Citation.CitedFigure;
import com.finalysis.chat.citation.Citation.CitedTxn;
import com.finalysis.chat.citation.Citation.DrillDown;
import com.finalysis.chat.grounding.AnswerCheck;
import com.finalysis.chat.grounding.CitationMarkers;
import com.finalysis.chat.grounding.GroundedSet;
import com.finalysis.chat.tool.FigureBasis;
import com.finalysis.chat.tool.RefRegistry;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Citations (CHT-05, docs/plans/phase-6.md §6), resolved locally and never sent to the model. Two sources, merged:
 * the markers the answer kept (each a ref this turn issued), then the refs behind its matched figures, so a grounded
 * figure always points at its source even without a marker. Markers are renumbered {@code [1]}, {@code [2]}… in order
 * of first appearance; refs found only through figures follow. Each ref resolves through its target: a txn with its
 * statement and source line, a total with its drill-downs (and for every quoted figure, the drill-downs it is the sum
 * of), an alert with its txns, a series with its drill-down. All in one read-only transaction, so a drill-down's
 * total and its sample rows agree.
 */
@Component
public class CitationResolver {

    /** Sample txns per drill-down (§6). */
    static final int SAMPLE = 5;

    private final CitationQuery txns;
    private final DrillDownService drillDowns;

    public CitationResolver(CitationQuery txns, DrillDownService drillDowns) {
        this.txns = txns;
        this.drillDowns = drillDowns;
    }

    /**
     * The answer's text with its markers renumbered, and its citations in marker order.
     *
     * @param dropped markers removed because the turn never issued them ({@code citationsDropped})
     */
    public record CitedAnswer(String text, List<Citation> citations, int dropped) {

        public CitedAnswer {
            citations = List.copyOf(citations);
        }
    }

    /** A grounded model answer: its markers, then the refs of its matched figures. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CitedAnswer cite(AnswerCheck check, RefRegistry refs) {
        Map<String, List<GroundedSet.Value>> cited = new LinkedHashMap<>();
        check.markers().forEach(m -> cited.computeIfAbsent(m.ref(), r -> new ArrayList<>()));
        for (AnswerCheck.FigureMatch match : check.matches()) {
            for (GroundedSet.Value value : match.cited()) {
                List<GroundedSet.Value> figures = cited.computeIfAbsent(value.ref(), r -> new ArrayList<>());
                if (figures.stream().noneMatch(v -> v.field().equals(value.field()))) {
                    figures.add(value);
                }
            }
        }
        return resolve(check.text(), cited, refs, check.dropped());
    }

    /** The Java-built fallback (§5.5): its markers are refs of the results it lists, cited as markers. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CitedAnswer citeFallback(String fallback, RefRegistry refs) {
        CitationMarkers.Parsed parsed = CitationMarkers.parse(fallback, refs::issued);
        Map<String, List<GroundedSet.Value>> cited = new LinkedHashMap<>();
        parsed.refs().forEach(ref -> cited.put(ref, List.of()));
        return resolve(parsed.text(), cited, refs, parsed.dropped());
    }

    /**
     * A stored citation read again (§6): every txn in it re-resolved by fingerprint, so a txn whose statement was
     * deleted shows {@code GONE} instead of stale text. Drill-down totals are kept as they were stored.
     */
    @Transactional(readOnly = true)
    public Citation refresh(Citation citation) {
        Set<String> fingerprints = new HashSet<>();
        visitTxns(citation, t -> {
            if (t.fingerprint() != null) {
                fingerprints.add(t.fingerprint());
            }
            return t;
        });
        Map<String, CitedTxn> now = txns.byFingerprints(fingerprints);
        return visitTxns(citation, t -> t.fingerprint() == null ? CitedTxn.gone(null)
                : now.getOrDefault(t.fingerprint(), CitedTxn.gone(t.fingerprint())));
    }

    private CitedAnswer resolve(String text, Map<String, List<GroundedSet.Value>> cited, RefRegistry refs,
                                int dropped) {
        Map<String, Integer> numbers = new LinkedHashMap<>();
        cited.keySet().forEach(ref -> numbers.put(ref, numbers.size() + 1));
        Map<Map<String, String>, DrillDown> resolved = new HashMap<>();
        List<Citation> citations = new ArrayList<>();
        for (Map.Entry<String, List<GroundedSet.Value>> e : cited.entrySet()) {
            int marker = numbers.get(e.getKey());
            RefRegistry.Target target = refs.target(e.getKey()).orElseThrow();
            citations.add(switch (target) {
                case RefRegistry.Txn t -> new Citation.Txn(marker, txns(List.of(t.txnId())).getFirst());
                case RefRegistry.Cell c -> new Citation.Total(marker, c.label(),
                        e.getValue().stream().map(v -> figure(v, c, resolved)).toList(),
                        c.drillDowns().stream().map(d -> drillDown(d, resolved)).toList());
                case RefRegistry.Alert a -> new Citation.Alert(marker, a.key(), a.type(), a.month(), txns(a.txnIds()));
                case RefRegistry.Series s -> new Citation.Series(marker, s.accountId(), s.merchant(),
                        drillDown(params("from", s.from().toString(), "to", s.to().toString(),
                                "accountId", Long.toString(s.accountId()), "merchant", s.merchant()), resolved));
            });
        }
        return new CitedAnswer(CitationMarkers.renumber(text, numbers), citations, dropped);
    }

    /** A quoted figure and what it rests on; a field the cell has no basis for is derived from the cell's rows. */
    private CitedFigure figure(GroundedSet.Value value, RefRegistry.Cell cell,
                               Map<Map<String, String>, DrillDown> resolved) {
        FigureBasis basis = Optional.ofNullable(cell.figures().get(value.field()))
                .orElseGet(() -> FigureBasis.derived(cell.drillDowns().stream()
                        .map(d -> new FigureBasis.Term(1, d)).toList()));
        return new CitedFigure(value.field(), value.returned(), basis.relation(), basis.component(),
                basis.terms().stream().map(t -> new Citation.Term(t.sign(), drillDown(t.drillDown(), resolved)))
                        .toList());
    }

    private DrillDown drillDown(Map<String, String> params, Map<Map<String, String>, DrillDown> resolved) {
        return resolved.computeIfAbsent(params, p -> {
            DrillDownResponse response = drillDowns.transactions(filter(p));
            List<CitedTxn> samples = txns(response.items().stream().map(DrillDownResponse.Item::id).toList());
            return new DrillDown(DrillDown.PATH, new TreeMap<>(p), response.total(), samples);
        });
    }

    /** The txns, in the order asked; one that no longer exists is {@code GONE}. */
    private List<CitedTxn> txns(List<Long> ids) {
        Map<Long, CitedTxn> found = txns.byIds(ids);
        return ids.stream().map(id -> found.getOrDefault(id, CitedTxn.gone(null))).toList();
    }

    /** The drill-down a ref's parameters name, first page of {@value #SAMPLE} rows. */
    static DrillDownFilter filter(Map<String, String> p) {
        return new DrillDownFilter(Optional.ofNullable(p.get("month")).map(YearMonth::parse),
                Optional.ofNullable(p.get("from")).map(YearMonth::parse),
                Optional.ofNullable(p.get("to")).map(YearMonth::parse),
                Optional.ofNullable(p.get("bucket")).map(TxnBucket::valueOf),
                Optional.ofNullable(p.get("measure")).map(TrendMeasure::valueOf),
                Optional.ofNullable(p.get("accountId")).map(Long::valueOf),
                Optional.ofNullable(p.get("categoryId")).map(Long::valueOf),
                Optional.ofNullable(p.get("merchant")), "true".equals(p.get("noMerchant")), 0, SAMPLE);
    }

    private static Map<String, String> params(String... pairs) {
        Map<String, String> params = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                params.put(pairs[i], pairs[i + 1]);
            }
        }
        return params;
    }

    /** The citation with every txn in it (the cited txn, an alert's, drill-down samples) replaced. */
    private static Citation visitTxns(Citation citation, Function<CitedTxn, CitedTxn> f) {
        return switch (citation) {
            case Citation.Txn t -> new Citation.Txn(t.marker(), f.apply(t.txn()));
            case Citation.Alert a -> new Citation.Alert(a.marker(), a.alertKey(), a.type(), a.month(),
                    a.txns().stream().map(f).toList());
            case Citation.Series s -> new Citation.Series(s.marker(), s.accountId(), s.merchant(),
                    visit(s.drillDown(), f));
            case Citation.Total t -> new Citation.Total(t.marker(), t.label(), t.figures().stream()
                    .map(fig -> new CitedFigure(fig.field(), fig.value(), fig.relation(), fig.component(),
                            fig.terms().stream().map(term -> new Citation.Term(term.sign(),
                                    visit(term.drillDown(), f))).toList()))
                    .toList(), t.drillDowns().stream().map(d -> visit(d, f)).toList());
        };
    }

    private static DrillDown visit(DrillDown d, Function<CitedTxn, CitedTxn> f) {
        return new DrillDown(d.path(), d.params(), d.total(), d.sampleTxns().stream().map(f).toList());
    }
}
