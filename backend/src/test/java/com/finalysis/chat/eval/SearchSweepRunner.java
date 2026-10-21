package com.finalysis.chat.eval;

import com.finalysis.chat.ToolCall;
import com.finalysis.chat.ToolExecutor;
import com.finalysis.chat.ToolOutcome;
import com.finalysis.chat.ToolStatus;
import com.finalysis.chat.eval.ChatEvalResults.IndexBuild;
import com.finalysis.chat.eval.SearchSweep.Candidate;
import com.finalysis.chat.eval.SearchSweep.QueryResult;
import com.finalysis.chat.outbound.OutboundTurnFactory;
import com.finalysis.chat.tool.view.SearchView;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the retrieval-only sweep (docs/plans/phase-6.md §4.3, step 8) on the dev set: for each dev question with search
 * targets, each of its queries ({@value #QUERIES}) goes through the real {@code search_transactions} path
 * ({@link ToolExecutor}: the search text redacted and checked, embedded with the embedding role, the index's nearest
 * merchants, the directory's outbound names), once, at the lowest floor and the tool's largest limit; then
 * {@link SearchSweep} works out every higher floor. Embeddings only: no chat model is called.
 */
class SearchSweepRunner {

    static final String QUERIES = "chat/eval/dev-search-queries.yml";

    private final ChatEvalSeeder seeder;
    private final OutboundTurnFactory turns;
    private final ToolExecutor executor;
    private final JsonMapper json = JsonMapper.builder().build();

    SearchSweepRunner(ChatEvalSeeder seeder, OutboundTurnFactory turns, ToolExecutor executor) {
        this.seeder = seeder;
        this.turns = turns;
        this.executor = executor;
    }

    record Sweep(String set, String setSha256, List<IndexBuild> index, List<QueryResult> queries,
                 List<SearchSweep.FloorSummary> floors, BigDecimal highestLossless) {
    }

    Sweep run(ChatEvalSet set, Map<String, List<String>> queries) throws Exception {
        if (!set.name().equals(ChatEvalGuards.DEV)) {
            throw new ChatEvalGuards.EvalRefusedException("The search sweep runs on the dev set only");
        }
        List<ChatEvalSet.Question> searched = set.questions().stream()
                .filter(q -> !q.searchTargets().isEmpty()).toList();
        List<IndexBuild> index = new ArrayList<>();
        List<QueryResult> results = new ArrayList<>();
        for (String dataset : searched.stream().map(ChatEvalSet.Question::dataset).distinct().toList()) {
            index.add(seeder.seed(dataset).index());
            for (ChatEvalSet.Question question : searched) {
                if (!question.dataset().equals(dataset)) {
                    continue;
                }
                for (String query : queries.getOrDefault(question.id(), List.of())) {
                    results.add(search(question, query));
                }
            }
        }
        seeder.clear();
        return new Sweep(set.name(), set.sha256(), index, results, SearchSweep.summarize(results),
                SearchSweep.highestLossless(results));
    }

    private QueryResult search(ChatEvalSet.Question question, String query) {
        String arguments = json.writeValueAsString(Map.of("query", query, "limit", 10));
        ToolOutcome outcome = executor.execute(new ToolCall("search_transactions", arguments), turns.start());
        List<Candidate> candidates = new ArrayList<>();
        if (outcome.status() == ToolStatus.OK && outcome.view() instanceof SearchView view) {
            for (SearchView.Candidate c : view.candidates()) {
                candidates.add(new Candidate(c.merchant(), c.match(),
                        c.similarity() == null ? null : new BigDecimal(c.similarity())));
            }
        }
        String status = outcome.status() != ToolStatus.OK ? outcome.status().name()
                : outcome.view() instanceof SearchView view && !Boolean.TRUE.equals(view.byMeaning())
                ? "NAME_ONLY" : "OK";
        return SearchSweep.query(question.id(), query, question.searchTargets(), status, candidates);
    }

    /** The sweep's queries by dev question id, in file order. */
    static Map<String, List<String>> loadQueries() {
        try (InputStream in = SearchSweepRunner.class.getClassLoader().getResourceAsStream(QUERIES)) {
            if (in == null) {
                throw new IllegalStateException(QUERIES + " is missing");
            }
            Map<String, Object> raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            Map<String, List<String>> queries = new LinkedHashMap<>();
            raw.forEach((id, list) -> queries.put(id, ((List<?>) list).stream().map(String::valueOf).toList()));
            return queries;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
