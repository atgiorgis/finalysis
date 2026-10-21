package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.ai.ChatSearchProperties;
import com.finalysis.ai.outbound.RedactionContextQuery;
import com.finalysis.categorize.AiCategorizer.Status;
import com.finalysis.categorize.MerchantEmbeddingStage.EmbeddingRunResult;
import com.finalysis.support.AiIntegrationTest;
import com.finalysis.support.FakeEmbeddingModel;
import com.finalysis.support.ImportTestSupport;
import java.nio.charset.StandardCharsets;
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
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The chat search index refresh (CHT-02; docs/plans/phase-6.md §4.2) on the synthetic similar-merchant cases: every
 * merchant whose outbound text may leave is embedded once per model and text, on categorization's path (redacted,
 * guard-checked, hashed, one AI-06 line per call), never a person-to-person one; the cap; the endpoint.
 */
@AiIntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class MerchantIndexRefreshTest {

    static final String CASES = "edge-cases/ai-categorization/similar-merchant-cases.csv";

    private final JdbcTemplate jdbc;
    private final CategorizeRows rows;
    private final FakeEmbeddingModel embeddingModel;
    private final MerchantIndexRefresh refresh;
    private final MerchantEmbeddingStage stage;
    private final CategorizationPromptBuilder outbound;
    private final RedactionContextQuery redaction;
    private final PlatformTransactionManager transactions;
    private final MockMvc mockMvc;

    MerchantIndexRefreshTest(JdbcTemplate jdbc, MerchantCleaner cleaner, FakeEmbeddingModel embeddingModel,
                             MerchantIndexRefresh refresh, MerchantEmbeddingStage stage,
                             CategorizationPromptBuilder outbound, RedactionContextQuery redaction,
                             PlatformTransactionManager transactions, MockMvc mockMvc) {
        this.jdbc = jdbc;
        this.rows = new CategorizeRows(jdbc, cleaner);
        this.embeddingModel = embeddingModel;
        this.refresh = refresh;
        this.stage = stage;
        this.outbound = outbound;
        this.redaction = redaction;
        this.transactions = transactions;
        this.mockMvc = mockMvc;
    }

    @BeforeEach
    void insertCases() {
        embeddingModel.reset();
        jdbc.update("DELETE FROM merchant_embedding");
        rows.account("Index Test Checking", "CHECKING", "4343");
        new String(ImportTestSupport.sample(CASES), StandardCharsets.UTF_8).lines().skip(1)
                .map(line -> line.split(",", -1))
                .forEach(cells -> rows.txn(cells[2], cells[3], cells[1]));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM merchant_embedding");
        rows.deleteAccounts();
        embeddingModel.reset();
    }

    private int sendableMerchants() {
        return jdbc.queryForObject("SELECT count(DISTINCT merchant) FROM txn WHERE merchant IS NOT NULL",
                Integer.class) - 1; // the person-to-person payment
    }

    @Test
    void everySendableMerchantIsEmbeddedOnceAsItsOutboundText(CapturedOutput output) {
        EmbeddingRunResult result = refresh.refresh();

        assertThat(result.status()).isEqualTo(Status.DONE);
        assertThat(result.merchants()).isEqualTo(sendableMerchants());
        assertThat(result.embedded()).isEqualTo(result.merchants()).isEqualTo(result.calls());
        assertThat(result.remaining()).isZero();
        assertThat(embeddingModel.inputs()).hasSize(result.calls()).doesNotHaveDuplicates()
                .contains("QUORVANE BAKEHOUSE", "PELTRIDGE BAKEHOUSE", "VANTERHOLM OVENS")
                .allSatisfy(text -> assertThat(text).doesNotContain("7700224466", "INDN", "JANE", "ZELLE", "CO ID",
                        "3QM7L2"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_embedding WHERE merchant LIKE 'ZELLE%'",
                Integer.class)).isZero();
        // the stored hash is the hash of the outbound text, as categorization's own vectors
        assertThat(jdbc.queryForObject("SELECT input_sha256 FROM merchant_embedding WHERE merchant = ?",
                String.class, "QUORVANE BAKEHOUSE")).isEqualTo(MerchantEmbeddingStage.sha256("QUORVANE BAKEHOUSE"));
        assertThat(output).containsPattern("ai call role=embedding model=" + AiIntegrationTest.EMBEDDING_MODEL
                + " items=1 inputTokens=\\d+ ms=\\d+ outcome=OK");
        assertThat(output).contains("Search index refresh: status=DONE merchants=" + result.merchants());
        assertThat(output.getOut().lines().filter(l -> l.contains("Search index refresh")))
                .allSatisfy(line -> assertThat(line).doesNotContain("BAKEHOUSE"));
    }

    @Test
    void aSecondRefreshMakesNoCall() {
        refresh.refresh();
        int calls = embeddingModel.inputs().size();

        EmbeddingRunResult again = refresh.refresh();

        assertThat(again.calls()).isZero();
        assertThat(again.cached()).isEqualTo(again.merchants());
        assertThat(embeddingModel.inputs()).hasSize(calls);
    }

    @Test
    void vectorsOfAnotherModelDontCountAsIndexed() {
        refresh.refresh();
        jdbc.update("UPDATE merchant_embedding SET model = 'retired-model' WHERE merchant = 'QUORVANE BAKEHOUSE'");
        embeddingModel.reset();

        EmbeddingRunResult result = refresh.refresh();

        assertThat(result.embedded()).isOne();
        assertThat(embeddingModel.inputs()).containsExactly("QUORVANE BAKEHOUSE");
    }

    @Test
    void theCapLeavesTheRestForTheNextRefresh() {
        MerchantIndexRefresh capped = new MerchantIndexRefresh(stage, outbound, redaction,
                new ChatSearchProperties(0.40, 2), jdbc, transactions);

        EmbeddingRunResult first = capped.refresh();

        assertThat(first.status()).isEqualTo(Status.CAP_REACHED);
        assertThat(first.calls()).isEqualTo(2);
        assertThat(first.remaining()).isEqualTo(sendableMerchants() - 2);
        assertThat(capped.refresh().embedded()).isEqualTo(2);
    }

    @Test
    void aFailedCallStopsTheRefreshAndTheNextOneCatchesUp() {
        embeddingModel.failingWith(new IllegalStateException("throttled"));

        EmbeddingRunResult failed = refresh.refresh();

        assertThat(failed.status()).isEqualTo(Status.STOPPED_ON_ERROR);
        assertThat(failed.embedded()).isZero();
        embeddingModel.reset();
        assertThat(refresh.refresh().embedded()).isEqualTo(sendableMerchants());
    }

    @Test
    void theEndpointReturnsCountsOnly() throws Exception {
        mockMvc.perform(post("/api/chat/search-index/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.embedded").value(sendableMerchants()))
                .andExpect(jsonPath("$.length()").value(8));
        assertThat(List.of(jdbc.queryForObject("SELECT count(*) FROM merchant_embedding", Integer.class)))
                .containsExactly(sendableMerchants());
    }
}
