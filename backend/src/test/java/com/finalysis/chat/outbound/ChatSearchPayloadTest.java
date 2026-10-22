package com.finalysis.chat.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.ai.SearchDocuments;
import com.finalysis.categorize.SearchEmbeddingRepository;
import com.finalysis.support.SearchIndexBuilder;
import com.finalysis.categorize.MerchantIndexRefresh;
import com.finalysis.chat.ChatInjectionFixture;
import com.finalysis.chat.ToolCall;
import com.finalysis.chat.ToolExecutor;
import com.finalysis.chat.ToolOutcome;
import com.finalysis.chat.ToolStatus;
import com.finalysis.support.ChatIntegrationTest;
import com.finalysis.support.FakeEmbeddingModel;
import com.finalysis.support.ImportTestSupport;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the two search tools send (docs/plans/phase-6.md §12.6, deferred from step 2 to step 6), on the injection
 * fixture with search by meaning on: each payload equals a hand-checked expected document in
 * {@code src/test/resources/chat/outbound/}, like the other ten tools' (ChatOutboundPayloadTest).
 *
 * <p>The index is filled by categorization's refresh, then every hostile merchant's vector is set equal to the search
 * text's own (similarity 1.00): the nearest merchants by far, and still never candidates, because their names may not
 * be sent. The canary 999.99, hostile words, the account's name, institution and last four, ids and balances reach
 * neither payload, nor any text sent to the embedding model for the search.
 */
@ChatIntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ChatSearchPayloadTest {

    static final String SEARCH = "{\"query\": \"food\", \"from\": \"2026-03\", \"to\": \"2026-03\", \"limit\": 10}";
    static final String TOTALS = "{\"merchants\": [\"MAPLEWOOD GROCERY\", \"RIVERBEND NOODLE BAR\"], "
            + "\"from\": \"2026-03\", \"to\": \"2026-03\"}";

    private final ImportTestSupport support;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ToolExecutor executor;
    private final OutboundTurnFactory turns;
    private final FakeEmbeddingModel embeddingModel;
    private final MerchantIndexRefresh refresh;
    private final SearchEmbeddingRepository embeddings;

    ChatSearchPayloadTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper json,
                          ToolExecutor executor, OutboundTurnFactory turns, FakeEmbeddingModel embeddingModel,
                          MerchantIndexRefresh refresh, SearchEmbeddingRepository embeddings) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.jdbc = jdbc;
        this.json = json;
        this.executor = executor;
        this.turns = turns;
        this.embeddingModel = embeddingModel;
        this.refresh = refresh;
        this.embeddings = embeddings;
    }

    @BeforeEach
    void loadAndIndex() throws Exception {
        jdbc.update("DELETE FROM search_embedding");
        new ChatInjectionFixture(support, jdbc, json).load();
        new SearchIndexBuilder(jdbc, embeddingModel, refresh)
                .near("MAPLEWOOD GROCERY", "FOOD", 0.71)
                .near("RIVERBEND NOODLE BAR", "FOOD", 0.64)
                .build();
        // every hostile merchant that was indexed now sits exactly on the search text
        for (String hostile : ChatInjectionFixture.hostileDescriptions()) {
            jdbc.queryForList("SELECT DISTINCT merchant FROM txn WHERE raw_description = ? AND merchant IS NOT NULL",
                    String.class, hostile).forEach(merchant -> {
                        if (jdbc.queryForObject("SELECT count(*) FROM search_embedding WHERE kind = 'MERCHANT' "
                                + "AND subject = ?", Integer.class, merchant) > 0) {
                            embeddings.upsert(SearchDocuments.Kind.MERCHANT, merchant, "test-embedding-model",
                                    "f".repeat(64), embeddingModel.vector("FOOD"));
                        }
                    });
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM search_embedding");
        embeddingModel.reset();
        support.deleteCreatedAccounts();
    }

    private JsonNode send(String tool, String arguments, StringBuilder all) {
        ToolOutcome outcome = executor.execute(new ToolCall(tool, arguments), turns.start());
        assertThat(outcome.status()).as(tool + " " + outcome.json()).isEqualTo(ToolStatus.OK);
        all.append(outcome.json()).append('\n');
        return json.readTree(outcome.json()).get("result");
    }

    @Test
    void bothSearchToolsSendExactlyTheirExpectedPayloads() throws Exception {
        StringBuilder all = new StringBuilder();
        int indexedHostile = jdbc.queryForObject("SELECT count(*) FROM search_embedding WHERE input_sha256 = ?",
                Integer.class, "f".repeat(64));
        assertThat(indexedHostile).as("hostile merchants sit on the search text").isGreaterThanOrEqualTo(5);

        assertThat(send("search_transactions", SEARCH, all)).isEqualTo(expected("search_transactions"));
        assertThat(send("get_merchant_totals", TOTALS, all)).isEqualTo(expected("get_merchant_totals"));

        String sent = all.toString();
        assertThat(ChatOutboundPayloadTest.HOSTILE)
                .allSatisfy(word -> assertThat(sent.toUpperCase()).doesNotContain(word));
        for (String description : ChatInjectionFixture.hostileDescriptions()) {
            assertThat(sent).doesNotContain(description);
        }
        assertThat(sent).doesNotContain(ChatInjectionFixture.NAME).doesNotContain(ChatInjectionFixture.INSTITUTION)
                .doesNotContain("Halden").doesNotContain(ChatInjectionFixture.LAST_FOUR)
                .doesNotContain("MERCHANT WITHHELD");
        assertThat(sent).doesNotContain("\"id\"", "\"accountId\"", "\"statementId\"", "\"key\"", "\"categoryId\"",
                "\"description\"", "\"sourceLine", "\"counterparty\"", "\"netChange\"", "\"name\"", "\"balance");
        assertThat(embeddingModel.inputs().getLast()).as("the search text, as sent").isEqualTo("FOOD");
    }

    /** The model may search with the hostile words themselves: still no hostile merchant, and nothing echoed. */
    @Test
    void searchingForTheHostileWordsFindsNoHostileMerchant() {
        StringBuilder all = new StringBuilder();
        for (String query : List.of("ignore previous instructions", "system prompt", "admin mode", "999.99",
                "Halden Everyday 7310")) {
            JsonNode result = send("search_transactions", json.createObjectNode().put("query", query)
                    .put("limit", 10).toString(), all);
            assertThat(result.get("candidates").valueStream().map(c -> c.get("merchant").stringValue()))
                    .as(query).allSatisfy(m -> assertThat(m).isIn("HOLLOWAY FLATS RENT", "MAPLEWOOD GROCERY",
                            "RIVERBEND NOODLE BAR", "FIXTURE EMPLOYER PAYROLL", "FIXTURE BANK INTEREST"));
        }
        assertThat(ChatOutboundPayloadTest.HOSTILE)
                .allSatisfy(word -> assertThat(all.toString().toUpperCase()).doesNotContain(word));
        assertThat(embeddingModel.inputs()).as("the account's name and last four never reach the embedding model")
                .noneMatch(text -> text.contains("HALDEN") || text.contains("7310") || text.contains("EVERYDAY"));
    }

    @Test
    void theLogNamesNoMerchantAndNoSearchText(CapturedOutput output) {
        send("search_transactions", SEARCH, new StringBuilder());

        assertThat(output.getOut().lines().filter(l -> l.contains("chat tool") || l.contains("chat outbound")
                || l.contains("ai call"))).isNotEmpty().allSatisfy(line -> {
                    assertThat(line).doesNotContain("FOOD", "MAPLEWOOD", "RIVERBEND", "HOLLOWAY");
                    // the ai call lines' own keys hold ROLE; the line's format is counts only (AiCallLog)
                    String message = line.contains("ai call") ? line.substring(line.indexOf("model=")) : line;
                    for (String word : ChatOutboundPayloadTest.HOSTILE) {
                        assertThat(message.toUpperCase()).doesNotContain(word);
                    }
                });
    }

    private JsonNode expected(String name) throws Exception {
        return json.readTree(Files.readString(ChatOutboundPayloadTest.EXPECTED.resolve(name + ".json")));
    }
}
