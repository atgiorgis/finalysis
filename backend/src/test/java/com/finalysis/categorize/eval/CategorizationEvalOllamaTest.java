package com.finalysis.categorize.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ai.AiPath;
import com.finalysis.ai.AiPathSettings;
import com.finalysis.ai.AiProviders;
import com.finalysis.categorize.CategorizationPromptBuilder;
import com.finalysis.categorize.eval.EvalRunner.EvalInputs;
import com.finalysis.categorize.eval.EvalRunner.EvalResult;
import com.finalysis.categorize.eval.EvalRunner.Variant;
import com.finalysis.categorize.eval.EvalRunner.VariantResult;
import com.finalysis.chat.eval.ChatEvalTarget;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import com.finalysis.support.OllamaStub;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The categorization evaluation on Ollama (9c-10), run as {@code CategorizationLiveEvalIT} runs it with
 * {@code -Deval.provider=ollama}, against a stub on loopback (the Local path): the context starts Off, the run switches
 * to the target's path, the AI variants' calls go to the stub's {@code /api/chat} with the model tag and their
 * embeddings to {@code /api/embed}, and the report records the path, the host, the tag, Ollama's version, every call's
 * time, and no cost. On the harness's nine synthetic rows; the stub answers each batch from the keys it was sent.
 */
@IntegrationTest
@Import({EvalSeeder.class, EvalRunner.class})
@TestPropertySource(properties = {
        "finalysis.ai.categorization.run-after-import=false",
        "finalysis.ai.ollama.models.chat=" + CategorizationEvalOllamaTest.MODEL})
class CategorizationEvalOllamaTest {

    static final String MODEL = "test-ollama-chat";
    static final OllamaStub STUB = OllamaStub.start(MODEL + ":latest", "nomic-embed-text:latest");
    private static final Path CASES = ImportTestSupport.SAMPLE_DATA.resolve(
            "edge-cases/ai-categorization/eval-harness-cases.csv");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void stubPort(DynamicPropertyRegistry registry) {
        registry.add("finalysis.ai.ollama.port", STUB::port);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    private final EvalRunner runner;
    private final AiProviders providers;
    private final JdbcTemplate jdbc;

    CategorizationEvalOllamaTest(EvalRunner runner, AiProviders providers, JdbcTemplate jdbc) {
        this.runner = runner;
        this.providers = providers;
        this.jdbc = jdbc;
    }

    /** The switch is saved in the vault's settings: back to Off, as {@code AiPathSwitchTest} leaves it. */
    @AfterEach
    void cleanUp() {
        providers.switchTo(new AiPathSettings.Selection(AiPath.OFF, null, null), Duration.ofSeconds(30));
        jdbc.update("DELETE FROM app_setting WHERE key LIKE 'ai.%'");
        STUB.reset();
        for (Long account : jdbc.queryForList("SELECT id FROM account WHERE name = ?", Long.class,
                EvalSeeder.ACCOUNT_NAME)) {
            jdbc.update("DELETE FROM txn WHERE account_id = ?", account);
            jdbc.update("DELETE FROM statement WHERE account_id = ?", account);
            jdbc.update("DELETE FROM csv_mapping WHERE account_id = ?", account);
            jdbc.update("DELETE FROM account WHERE id = ?", account);
        }
        jdbc.update("DELETE FROM merchant_embedding");
    }

    /** Every item of the batch answered Groceries at 0.92, by the keys the prompt carries. */
    private static OllamaStub.Reply answer(JsonNode body) {
        String user = body.get("messages").get(1).get("content").stringValue();
        String open = CategorizationPromptBuilder.OPEN_TAG;
        JsonNode items = JSON.readTree(user.substring(user.indexOf(open) + open.length(),
                user.lastIndexOf(CategorizationPromptBuilder.CLOSE_TAG))).get("items");
        List<Map<String, Object>> answers = new ArrayList<>();
        items.forEach(item -> answers.add(Map.of("key", item.get("key").stringValue(), "category", "Groceries",
                "confidence", 0.92)));
        return OllamaStub.text(JSON.writeValueAsString(Map.of("answers", answers)));
    }

    @Test
    void theRunGoesToTheOllamaPathAndTheReportSaysWhereHowLongAndNoCost() throws Exception {
        STUB.chatAnswering(CategorizationEvalOllamaTest::answer);
        assertThat(providers.state().path()).as("the context starts Off, as the IT's does on Ollama")
                .isEqualTo(AiPath.OFF);
        ChatEvalTarget target = CategorizationLiveEvalIT.target(CategorizationLiveEvalIT.DEV, "ollama",
                "http://127.0.0.1:11434").connect(providers);

        EvalResult result = runner.run(EvalSet.load("harness", CASES),
                new EvalInputs(new BigDecimal("0.70"), 0.97, true), EvalPrices.load(), target);

        assertThat(result.target().path()).isEqualTo(AiPath.LOCAL);
        assertThat(result.target().ollamaVersion()).isEqualTo(OllamaStub.VERSION);
        assertThat(result.model()).isEqualTo(MODEL);
        assertThat(STUB.requests("/api/chat")).as("B, C and D each called the stub").hasSizeGreaterThanOrEqualTo(3)
                .allSatisfy(r -> assertThat(r.json().get("model").stringValue()).isEqualTo(MODEL));
        assertThat(STUB.requests("/api/embed")).as("C and D embedded through the stub").isNotEmpty();

        VariantResult b = result.variants().stream().filter(v -> v.variant() == Variant.B).findFirst().orElseThrow();
        assertThat(b.status()).isEqualTo("DONE");
        assertThat(b.metrics().categorized()).as("rules plus the stub's answers").isGreaterThan(4);
        assertThat(b.calls()).as("every call's time, from AiCallLog").isNotEmpty()
                .allSatisfy(call -> assertThat(call.role()).isEqualTo("categorization"))
                .allSatisfy(call -> assertThat(call.outcome()).isEqualTo("OK"));
        VariantResult c = result.variants().stream().filter(v -> v.variant() == Variant.C).findFirst().orElseThrow();
        assertThat(c.calls()).extracting(AiCallTimings.Call::role).contains("categorization", "embedding");
        assertThat(result.variants()).filteredOn(v -> v.variant() != Variant.A)
                .allSatisfy(v -> assertThat(v.usage().estimatedCostUsd()).as("no cost on Ollama").isNull());

        EvalReport report = EvalReport.of(new EvalGuards.GitState("0123456789abcdef", false),
                Instant.parse("2026-11-03T12:00:00Z"), result);
        String markdown = report.markdown();
        assertThat(markdown)
                .contains("- Path: Local (Ollama " + OllamaStub.VERSION + "), host `127.0.0.1`; model tag `" + MODEL
                        + "`\n")
                .contains("- Prices: none: Ollama has no token cost\n")
                .contains("## Call times")
                .contains("| B | categorization | ")
                .contains("| C | embedding | ");
        assertThat(report.consoleSummary()).contains("model tag `" + MODEL + "`");
        String summary = markdown.substring(markdown.indexOf("## Summary"), markdown.indexOf("## By source"));
        assertThat(summary.lines().filter(line -> line.startsWith("| B") || line.startsWith("| C")
                || line.startsWith("| D"))).allSatisfy(line -> assertThat(line).contains("| none |"));
    }

    @Test
    void onOllamaOnlyTheDevSetRunsAndAModelTagIsNeeded() {
        assertThatThrownBy(() -> CategorizationLiveEvalIT.target("harness", "ollama", "http://127.0.0.1:11434"))
                .hasMessage("-Deval.provider=ollama runs the dev set only: -Deval.set=dev");
        assertThat(CategorizationLiveEvalIT.target("harness", null, null).ollama())
                .as("Cloud keeps its sets").isFalse();
        assertThatThrownBy(() -> CategorizationLiveEvalIT.ollamaModel(" "))
                .hasMessage("Choose the Ollama model: -Deval.model=<tag>");
        assertThat(CategorizationLiveEvalIT.ollamaModel(" ministral-3:14b ")).isEqualTo("ministral-3:14b");
    }
}
