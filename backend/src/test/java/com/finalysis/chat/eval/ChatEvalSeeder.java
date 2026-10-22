package com.finalysis.chat.eval;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.analytics.SampleDataImport;
import com.finalysis.categorize.MerchantEmbeddingStage.EmbeddingRunResult;
import com.finalysis.categorize.MerchantIndexRefresh;
import com.finalysis.chat.ChatInjectionFixture;
import com.finalysis.chat.eval.ChatEvalResults.IndexBuild;
import com.finalysis.support.ImportTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seeds one dataset of a chat question set (docs/plans/phase-6.md §13.1, step 8) the way the Phase 5 and 6 tests seed
 * it, on the evaluation's own Testcontainers database: imported through the real API, with the key's categories
 * applied, then the search index built by categorization's refresh before any question. One dataset at a time: the
 * previous one is deleted first, and seeding refuses a database that still holds txns.
 * <ul>
 *   <li>{@code SAMPLE}: {@code sample-data/statements/} with {@code answer-key.csv}'s categories, and the accounts
 *       named as a user would name them ({@link #SAMPLE_NAMES}, step 8b), so questions such as "my Summit card" have
 *       an account name to be redacted to its alias;</li>
 *   <li>{@code FIXTURE}: the analytics fixture ({@code sample-data/analytics/}) with its categories;</li>
 *   <li>{@code INJECTION}: the chat injection fixture ({@code sample-data/chat/injection/}).</li>
 * </ul>
 */
class ChatEvalSeeder {

    /**
     * The sample accounts' names and institutions, by slug: fictional, as in the statements (the descriptions say
     * "APEX VISA AUTOPAY", "SUMMIT CARD ONLINE PMT"). The first dev run seeded "Test 3001" at "Test Bank", so "Summit"
     * and "Apex" in a question named no account and went out as typed (d-l09, d-c06).
     */
    static final Map<String, List<String>> SAMPLE_NAMES = Map.of(
            "northfield-checking", List.of("Northfield Checking", "Northfield Credit Union"),
            "northfield-savings", List.of("Northfield Savings", "Northfield Credit Union"),
            "apex-visa", List.of("Apex Visa", "Apex Bank"),
            "summit-card", List.of("Summit Card", "Summit Financial"));

    private final ImportTestSupport support;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final MerchantIndexRefresh refresh;

    ChatEvalSeeder(MockMvc mvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper json,
                   MerchantIndexRefresh refresh) {
        this.support = new ImportTestSupport(mvc, accounts, jdbc);
        this.jdbc = jdbc;
        this.json = json;
        this.refresh = refresh;
    }

    /**
     * @param accounts account ids by the slug the set's citation locators use
     * @param labels   texts the extractor must not read as figures: category names and stored merchants
     */
    record Seeded(Map<String, Long> accounts, List<String> labels, IndexBuild index) {
    }

    /** Deletes whatever an earlier dataset left, then seeds this one and builds its search index. */
    Seeded seed(String dataset) throws Exception {
        clear();
        Integer left = jdbc.queryForObject("SELECT count(*) FROM txn", Integer.class);
        if (left == null || left != 0) {
            throw new IllegalStateException("The evaluation needs a database without txns; found " + left);
        }
        Map<String, Long> accounts = switch (dataset) {
            case "SAMPLE" -> {
                SampleDataImport sample = new SampleDataImport(support, json);
                Map<String, Long> ids = sample.load();
                sample.applyAnswerKey(jdbc, ids);
                ids.forEach((slug, id) -> {
                    List<String> names = SAMPLE_NAMES.get(slug);
                    jdbc.update("UPDATE account SET name = ?, institution = ? WHERE id = ?", names.get(0),
                            names.get(1), id);
                });
                yield ids;
            }
            case "FIXTURE" -> new AnalyticsFixture(support, jdbc, json).load().accounts();
            case "INJECTION" -> Map.of("injection-checking", new ChatInjectionFixture(support, jdbc, json).load());
            default -> throw new IllegalArgumentException("Unknown dataset " + dataset);
        };
        EmbeddingRunResult built = refresh.refresh();
        IndexBuild index = new IndexBuild(dataset, built.status().name(), built.merchants(), built.embedded(),
                built.cached(), built.calls(), built.remaining());
        List<String> labels = new ArrayList<>(jdbc.queryForList("SELECT name FROM category ORDER BY name",
                String.class));
        labels.addAll(jdbc.queryForList("SELECT DISTINCT merchant FROM txn WHERE merchant IS NOT NULL ORDER BY 1",
                String.class));
        return new Seeded(Map.copyOf(accounts), List.copyOf(labels), index);
    }

    /** Everything a dataset and its conversations leave: conversations, the ledger, the index and the accounts. */
    void clear() {
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_usage");
        jdbc.update("DELETE FROM merchant_embedding");
        jdbc.update("DELETE FROM search_embedding");
        support.deleteCreatedAccounts();
    }
}
