package com.finalysis.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.analytics.AnalyticsFixture.Loaded;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * UI-04 (docs/plans/phase-7.md §3.3, step 3b): a transaction shows the statement it came from and the line it was
 * imported from. On the analytics fixture, every txn's {@code sourceLine} is its file's own line at that number,
 * and its {@code statement} is the one it was imported into, with the manifest's period. Additive: the other fields
 * are unchanged ({@code TransactionApiTest}), and the list, correction and undo responses carry the same fields.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionSourceLineTest {

    private final MockMvc mockMvc;
    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final JsonMapper json;
    private Loaded loaded;

    TransactionSourceLineTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.mockMvc = mockMvc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, jsonMapper);
        this.json = jsonMapper;
    }

    @BeforeAll
    void importFixture() throws Exception {
        assertThat(support.count("SELECT count(*) FROM statement")).isZero();
        loaded = fixture.load();
    }

    @AfterAll
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    private JsonNode read(String path) throws Exception {
        return json.readTree(mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    @Test
    void everyTxnShowsItsOwnLineAndStatement() throws Exception {
        int checked = 0;
        for (AnalyticsFixture.Statement statement : AnalyticsFixture.manifest()) {
            List<String> lines = Files.readAllLines(AnalyticsFixture.DIR.resolve(statement.file()));
            long statementId = loaded.statements().get(statement.file());
            for (Map.Entry<String, Long> txn : loaded.txns().entrySet()) {
                if (!txn.getKey().startsWith(statement.file() + ":")) {
                    continue;
                }
                int line = Integer.parseInt(txn.getKey().substring(statement.file().length() + 1));
                JsonNode body = read("/api/transactions/" + txn.getValue());

                assertThat(body.get("sourceLine").get("number").asInt()).as(txn.getKey()).isEqualTo(line);
                assertThat(body.get("sourceLine").get("text").asString()).as(txn.getKey())
                        .isEqualTo(lines.get(line - 1));
                assertThat(body.get("statement").get("id").asLong()).isEqualTo(statementId)
                        .isEqualTo(body.get("statementId").asLong());
                assertThat(body.get("statement").get("periodStart").asString())
                        .isEqualTo(statement.periodStart().toString());
                assertThat(body.get("statement").get("periodEnd").asString())
                        .isEqualTo(statement.periodEnd().toString());
                checked++;
            }
        }
        assertThat(checked).as("every fixture txn").isEqualTo(loaded.txns().size()).isPositive();
    }

    @Test
    void theListCarriesTheSameFields() throws Exception {
        long id = loaded.txns().values().iterator().next();
        JsonNode one = read("/api/transactions/" + id);
        JsonNode page = read("/api/transactions?size=200");
        JsonNode listed = null;
        for (JsonNode item : page.get("items")) {
            if (item.get("id").asLong() == id) {
                listed = item;
            }
        }
        assertThat(listed).isNotNull();
        assertThat(listed.get("sourceLine")).isEqualTo(one.get("sourceLine"));
        assertThat(listed.get("statement")).isEqualTo(one.get("statement"));
    }
}
