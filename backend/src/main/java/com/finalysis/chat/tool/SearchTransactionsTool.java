package com.finalysis.chat.tool;

import static com.finalysis.chat.tool.Params.FROM;
import static com.finalysis.chat.tool.Params.TO;

import com.finalysis.analytics.MonthRange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code search_transactions} (CHT-02, docs/plans/phase-6.md §4.3): the merchants that mean what the user said, with
 * what each spent over the range. Merchants first: the model then totals or lists the ones it picks with the numeric
 * tools. Candidates are merchant texts, each standing for every stored merchant it covers; a merchant whose name may
 * not be sent is never a candidate.
 * <ol>
 *   <li>Name matches first, in name order: texts that hold every word of the search, so an exact name works without a
 *       vector;</li>
 *   <li>then the nearest merchants of the same embedding model at least {@code min-similarity}, nearest first;</li>
 *   <li>at most {@code limit}; each one's {@code spent} and {@code count} from the drill-down
 *       ({@link MerchantSpendReader}).</li>
 * </ol>
 * The search text was redacted, checked and embedded when the arguments were parsed, outside this read.
 */
@Component
class SearchTransactionsTool implements ChatTool {

    static final int MAX_LIMIT = 10;

    private static final ToolParam QUERY = ToolParam.required("query", new ToolParam.Search(),
            "What to look for, in a few words: a kind of merchant (coffee, rideshare, pharmacy) or part of a name.");

    private final MerchantSearchQuery search;
    private final MerchantSpendReader spend;

    SearchTransactionsTool(MerchantSearchQuery search, MerchantSpendReader spend) {
        this.search = search;
        this.spend = spend;
    }

    @Override
    public ToolName name() {
        return ToolName.SEARCH_TRANSACTIONS;
    }

    @Override
    public String description() {
        return "Finds the merchants that match what the user describes, by name and by meaning, when no category or "
                + "exact merchant fits. Returns candidates: merchant, match (NAME or MEANING), similarity (0 to 1, "
                + "MEANING only; not a figure for the answer), category, spent (a positive amount = money out after "
                + "refunds) and count over the range. Pick the candidates that fit, then call get_merchant_totals with "
                + "them for their combined total, or list_transactions with one. merchants and indexedMerchants tell how "
                + "much of the data search by meaning covers; byMeaning false means it searched by name only.";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(QUERY, FROM, TO, Params.limit(MAX_LIMIT));
    }

    @Override
    public List<FieldProblem> check(ToolArguments arguments) {
        return arguments.rangeProblems();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        SearchText query = arguments.search("query");
        int limit = arguments.number("limit");
        Map<String, ToolRead.Search.Candidate> found = new LinkedHashMap<>();
        for (String text : query.merchants().nameMatches(query.words())) {
            found.putIfAbsent(text, new ToolRead.Search.Candidate(ToolRead.Search.Match.NAME, null, null));
        }
        if (query.byMeaning()) {
            for (MerchantSearchQuery.Hit hit : search.nearest(query.vector(), query.model(), query.minSimilarity())) {
                // hits come nearest first, so a text's first hit is its best
                query.merchants().text(hit.merchant()).ifPresent(text -> found.putIfAbsent(text,
                        new ToolRead.Search.Candidate(ToolRead.Search.Match.MEANING, hit.similarity(), null)));
            }
        }
        List<ToolRead.Search.Candidate> candidates = new ArrayList<>();
        MonthRange range = null;
        for (Map.Entry<String, ToolRead.Search.Candidate> entry : found.entrySet()) {
            if (candidates.size() == limit) {
                break;
            }
            MerchantSpend spent = spend.read(new MerchantGroup(entry.getKey(),
                    query.merchants().stored(entry.getKey())), arguments.month("from"), arguments.month("to"),
                    Optional.empty());
            range = range == null ? spent.range() : range;
            candidates.add(new ToolRead.Search.Candidate(entry.getValue().match(), entry.getValue().similarity(),
                    spent));
        }
        return new ToolRead.Search(range, candidates, search.coverage(query.model()), query.byMeaning());
    }
}
