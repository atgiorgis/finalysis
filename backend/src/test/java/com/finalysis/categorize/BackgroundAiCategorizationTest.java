package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.support.AiIntegrationTest;
import com.finalysis.support.FakeChatModel;
import com.finalysis.support.FakeEmbeddingModel;
import com.finalysis.support.ImportTestSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The background run after an import (docs/plans/phase-4.md §1.2), with {@code run-after-import} turned on: it is off
 * by default in every profile ({@code AiCategorizerTest#anImportStartsNoAiByDefault}). A failing model never fails
 * the import, and the next import's run catches up.
 */
@AiIntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "finalysis.ai.categorization.run-after-import=true")
class BackgroundAiCategorizationTest {

    private final JdbcTemplate jdbc;
    private final CategorizeRows rows;
    private final FakeChatModel model;
    private final FakeEmbeddingModel embeddingModel;
    private final ImportTestSupport imports;

    BackgroundAiCategorizationTest(JdbcTemplate jdbc, MerchantCleaner cleaner, FakeChatModel model,
                                   FakeEmbeddingModel embeddingModel, MockMvc mockMvc, AccountRepository accounts) {
        this.jdbc = jdbc;
        this.rows = new CategorizeRows(jdbc, cleaner);
        this.model = model;
        this.embeddingModel = embeddingModel;
        this.imports = new ImportTestSupport(mockMvc, accounts, jdbc);
    }

    @BeforeEach
    void setUp() {
        model.reset();
        embeddingModel.reset();
        rows.account("AI Background Checking", "CHECKING", "4242");
    }

    @AfterEach
    void cleanUp() {
        model.reset();
        embeddingModel.reset();
        rows.deleteAccounts();
        imports.deleteCreatedAccounts();
        jdbc.update("DELETE FROM merchant_embedding");
    }

    @Test
    void anImportAsksForABackgroundRunAndAModelFailureNeverFailsIt(CapturedOutput output) throws Exception {
        // A row of sample-data/edge-cases/ai-categorization/ai-categorization-cases.csv, waiting for the AI.
        long toolworks = rows.txn("GANTRYWELL TOOLWORKS", "-129.99", "2026-05-04");
        model.failingWith(new IllegalStateException("GANTRYWELL TOOLWORKS -129.99"));
        long account = imports.account(AccountType.CHECKING, "1001");

        imports.upload(account, "april.ofx", ImportTestSupport.sample("ofx/northfield-checking_2026-04.ofx"))
                .andExpect(status().isCreated());

        waitFor(() -> runs(output) == 1);
        assertThat(output).contains("AI categorization call failed (java.lang.IllegalStateException)");
        assertThat(output).doesNotContain("GANTRYWELL");
        assertThat(rows.source(toolworks)).isNull();

        model.answering(ModelAnswers.answering(item -> "Shopping", 0.92), 300, 40);
        imports.upload(account, "may.ofx", ImportTestSupport.sample("ofx/northfield-checking_2026-05.ofx"))
                .andExpect(status().isCreated());
        waitFor(() -> runs(output) == 2);
        assertThat(output).contains("AI categorization: status=STOPPED_ON_ERROR", "AI categorization: status=DONE");
        assertThat(rows.category(toolworks)).isEqualTo("Shopping");
        assertThat(rows.source(toolworks)).isEqualTo("AI");
    }

    /** Background runs finished so far: each logs one summary line at its end. */
    private static int runs(CapturedOutput output) {
        return output.getOut().split("AI categorization: status=", -1).length - 1;
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("timed out waiting for the background run").isLessThan(deadline);
            Thread.sleep(50);
        }
    }
}
