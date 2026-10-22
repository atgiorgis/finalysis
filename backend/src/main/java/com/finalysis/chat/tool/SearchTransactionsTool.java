package com.finalysis.chat.tool;

import static com.finalysis.chat.tool.Params.MONTH;
import static com.finalysis.chat.tool.Params.TO;

import com.finalysis.analytics.MerchantTotalsResponse;
import com.finalysis.analytics.MerchantTotalsService;
import com.finalysis.analytics.MonthRange;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code search_transactions} (CHT-02, docs/plans/phase-6.md §4.3, step 8b): the merchants that mean what the user said,
 * with what each spent over the range. Merchants first: the model then totals or lists the ones it picks with the
 * numeric tools. Candidates are merchant texts, each standing for every stored merchant it covers; a merchant whose name
 * may not be sent is never a candidate.
 * <ol>
 *   <li>Name matches first, in name order: texts that hold every word of the search, so an exact name works without a
 *       vector;</li>
 *   <li>then the merchants of the categories whose name the search holds ("groceries"), by their spending;</li>
 *   <li>then, by meaning, nearest first: merchants whose document (text and category) is at least {@code min-similarity}
 *       from the search, and the merchants of each category at least that near, at their category's similarity
 *       (a merchant before a category's merchants at the same similarity). A category's merchants come from
 *       {@code MerchantTotalsService.merchants} over the range, by spending: "rideshare" can find a
 *       {@code Transportation} category whose merchants the model knows by name, when the brands' own vectors sit far
 *       from the word;</li>
 *   <li>at most {@code limit}; each one's {@code spent} and {@code count} from the drill-down
 *       ({@link MerchantSpendReader}).</li>
 * </ol>
 * The matched categories are listed too, so the model can answer with {@code get_category_totals} when the user meant
 * the whole category. Without a period the search covers every month with data ({@link DataMonths}, step 8c), so
 * "how much have I spent at the pharmacy?" is over all of it. The search text was redacted, checked and embedded when
 * the arguments were parsed, outside this read.
 */
@Component
class SearchTransactionsTool implements ChatTool {

    static final int MAX_LIMIT = 10;

    /** Most categories a search reports and draws merchants from. */
    static final int MAX_CATEGORIES = 3;

    private static final ToolParam QUERY = ToolParam.required("query", new ToolParam.Search(),
            "What to look for, in a few words: a kind of merchant (coffee, rideshare, pharmacy) or part of a name.");

    private final MerchantSearchQuery search;
    private final MerchantSpendReader spend;
    private final MerchantTotalsService merchantTotals;
    private final DataMonths dataMonths;

    SearchTransactionsTool(MerchantSearchQuery search, MerchantSpendReader spend,
                           MerchantTotalsService merchantTotals, DataMonths dataMonths) {
        this.search = search;
        this.spend = spend;
        this.merchantTotals = merchantTotals;
        this.dataMonths = dataMonths;
    }

    @Override
    public ToolName name() {
        return ToolName.SEARCH_TRANSACTIONS;
    }

    @Override
    public String description() {
        return "Finds merchants matching what the user describes, by name and meaning. Returns matched categories, "
                + "then candidates: merchant, match (NAME, MEANING, or CATEGORY: a merchant of a matched category; "
                + "keep it only if it fits), similarity (not a figure), category, spent (a positive amount = money "
                + "out after refunds) and count over the range. Then: get_merchant_totals with the ones that fit "
                + "(combined total), list_transactions with one, or get_category_totals for a whole category. Without "
                + "month or from: every month with data; from and to say which. searchIndex (only when some merchants "
                + "can't be found by meaning; it counts the index, not this search) or byMeaning false: it may have "
                + "missed some.";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(QUERY, MONTH, Params.ALL_DATA_FROM, TO, Params.limit(MAX_LIMIT));
    }

    @Override
    public List<FieldProblem> check(ToolArguments arguments) {
        return arguments.rangeProblems();
    }

    /** A candidate before its figures: its text, how it was found, and the rank it sorts by. */
    private record Found(String text, ToolRead.Search.Match match, BigDecimal similarity, int group) {
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        SearchText query = arguments.search("query");
        int limit = arguments.number("limit");
        DataMonths.Range months = dataMonths.resolve(arguments);
        Optional<YearMonth> from = months.from();
        Optional<YearMonth> to = months.to();
        List<ToolRead.Search.CategoryMatch> categories = categories(query);

        Map<String, Found> found = new LinkedHashMap<>();
        for (String text : query.merchants().nameMatches(query.words())) {
            found.putIfAbsent(text, new Found(text, ToolRead.Search.Match.NAME, null, 0));
        }
        List<Found> byMeaning = new ArrayList<>();
        for (ToolRead.Search.CategoryMatch category : categories) {
            for (String text : members(query, category.category(), from, to, limit)) {
                Found member = new Found(text, ToolRead.Search.Match.CATEGORY, category.similarity(), 1);
                if (category.similarity() == null) {
                    found.putIfAbsent(text, member);
                } else {
                    byMeaning.add(member);
                }
            }
        }
        if (query.byMeaning()) {
            for (MerchantSearchQuery.Hit hit : search.nearest(query.vector(), query.model(), query.minSimilarity())) {
                query.merchants().text(hit.merchant()).ifPresent(text -> byMeaning.add(
                        new Found(text, ToolRead.Search.Match.MEANING, hit.similarity(), 0)));
            }
        }
        // nearest first; at one similarity a merchant's own match before a category's merchants; else as found
        byMeaning.sort(Comparator.comparing(Found::similarity).reversed().thenComparingInt(Found::group));
        byMeaning.forEach(f -> found.putIfAbsent(f.text(), f));

        List<ToolRead.Search.Candidate> candidates = new ArrayList<>();
        MonthRange range = months.covered().orElse(null);
        for (Found f : found.values()) {
            if (candidates.size() == limit) {
                break;
            }
            MerchantSpend spent = spend.read(new MerchantGroup(f.text(), query.merchants().stored(f.text())), from, to,
                    Optional.empty());
            range = range == null ? spent.range() : range;
            candidates.add(new ToolRead.Search.Candidate(f.match(), f.similarity(), spent));
        }
        return new ToolRead.Search(range, categories, candidates, search.coverage(query.model()), query.byMeaning());
    }

    /**
     * The categories the search matched: by name first (every word of the name in the search), then by meaning,
     * nearest first; at most {@value #MAX_CATEGORIES}.
     */
    private List<ToolRead.Search.CategoryMatch> categories(SearchText query) {
        Map<String, ToolRead.Search.CategoryMatch> matched = new LinkedHashMap<>();
        query.categories().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).forEach(name -> {
            List<String> words = MerchantDirectory.wordsOf(name).stream()
                    .filter(w -> w.chars().anyMatch(Character::isLetterOrDigit)).toList();
            if (!words.isEmpty() && query.words().containsAll(words)) {
                matched.put(name, new ToolRead.Search.CategoryMatch(name, ToolRead.Search.Match.NAME, null));
            }
        });
        if (query.byMeaning()) {
            for (MerchantSearchQuery.CategoryHit hit : search.nearestCategories(query.vector(), query.model(),
                    query.minSimilarity())) {
                if (query.categories().containsKey(hit.category())) {
                    matched.putIfAbsent(hit.category(), new ToolRead.Search.CategoryMatch(hit.category(),
                            ToolRead.Search.Match.MEANING, hit.similarity()));
                }
            }
        }
        return matched.values().stream().limit(MAX_CATEGORIES).toList();
    }

    /** A category's nameable merchants over the range, by spending, as their texts; at most {@code limit}. */
    private List<String> members(SearchText query, String category, Optional<YearMonth> from, Optional<YearMonth> to,
                                 int limit) {
        MerchantTotalsResponse top = merchantTotals.merchants(from, to, Optional.empty(),
                Optional.of(query.categories().get(category)), limit);
        return top.items().stream()
                .map(MerchantTotalsResponse.MerchantTotal::merchant)
                .filter(Objects::nonNull)
                .map(stored -> query.merchants().text(stored))
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }
}
