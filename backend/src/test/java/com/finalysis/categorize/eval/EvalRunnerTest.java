package com.finalysis.categorize.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.finalysis.categorize.CategorizationPromptBuilder;
import com.finalysis.categorize.MerchantCleaner;
import com.finalysis.categorize.RedactionContext;
import com.finalysis.categorize.eval.EvalMetrics.Counts;
import com.finalysis.categorize.eval.EvalMetrics.RowSource;
import com.finalysis.categorize.eval.EvalMetrics.SweepPoint;
import com.finalysis.categorize.eval.EvalMetrics.VariantMetrics;
import com.finalysis.categorize.eval.EvalPrices.Price;
import com.finalysis.categorize.eval.EvalRunner.EvalInputs;
import com.finalysis.categorize.eval.EvalRunner.EvalResult;
import com.finalysis.categorize.eval.EvalRunner.Variant;
import com.finalysis.categorize.eval.EvalRunner.VariantResult;
import com.finalysis.categorize.eval.ExampleDiagnostics.FloorCount;
import com.finalysis.categorize.eval.ExampleDiagnostics.ItemNeighbours;
import com.finalysis.support.AiIntegrationTest;
import com.finalysis.support.FakeChatModel;
import com.finalysis.support.FakeEmbeddingModel;
import com.finalysis.support.ImportTestSupport;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The accuracy harness end to end (CAT-05; docs/plans/phase-4.md, step 9a notes), with a {@link FakeChatModel} whose
 * answers are scripted by merchant and a {@link FakeEmbeddingModel} with placed neighbours, on the nine rows of
 * {@code sample-data/edge-cases/ai-categorization/eval-harness-cases.csv}. Every expected number is worked out by
 * hand below. No cloud call is possible: this context has no Bedrock client, and {@code NoCloudAiGuard} is in force.
 *
 * <p>The rows: 1-2 a deli (seed rule, Dining), 3-4 ORRISWICK PANTRY (one item, Groceries), 5 VELLICOTT (Dining),
 * 6 BRISKWATER (Transportation), 7 MOSSGRAVE (Shopping), 8 payroll (seed rule, Income), 9 a Zelle payment (seed rule,
 * Person-to-Person Out). The model answers ORRISWICK and VELLICOTT right at 0.92, BRISKWATER wrong (Tolls) at 0.80
 * and MOSSGRAVE right at 0.55, below min-confidence 0.70. VELLICOTT sits at 0.98 from the deli, ORRISWICK at 0.80.
 */
@AiIntegrationTest
@Import({EvalSeeder.class, EvalRunner.class})
class EvalRunnerTest {

    private static final Path CASES = ImportTestSupport.SAMPLE_DATA.resolve(
            "edge-cases/ai-categorization/eval-harness-cases.csv");
    private static final EvalInputs INPUTS = new EvalInputs(new BigDecimal("0.70"), 0.97, true);
    private static final EvalPrices PRICES = new EvalPrices("test prices", Map.of(
            AiIntegrationTest.CATEGORIZATION_MODEL, new Price(new BigDecimal("1.00"), new BigDecimal("5.00")),
            AiIntegrationTest.EMBEDDING_MODEL, new Price(new BigDecimal("0.02"), BigDecimal.ZERO)));

    /** The model's scripted answers: category and confidence by a word of the item's merchant. */
    private static final Map<String, Object[]> ANSWERS = Map.of(
            "ORRISWICK", new Object[] {"Groceries", 0.92},
            "VELLICOTT", new Object[] {"Dining", 0.92},
            "BRISKWATER", new Object[] {"Tolls", 0.80},
            "MOSSGRAVE", new Object[] {"Shopping", 0.55});

    private final EvalRunner runner;
    private final FakeChatModel chat;
    private final FakeEmbeddingModel embeddingModel;
    private final MerchantCleaner cleaner;
    private final CategorizationPromptBuilder promptBuilder;
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    private EvalSet set;
    private String deli;

    EvalRunnerTest(EvalRunner runner, FakeChatModel chat, FakeEmbeddingModel embeddingModel, MerchantCleaner cleaner,
                   CategorizationPromptBuilder promptBuilder, JdbcTemplate jdbc) {
        this.runner = runner;
        this.chat = chat;
        this.embeddingModel = embeddingModel;
        this.cleaner = cleaner;
        this.promptBuilder = promptBuilder;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void setUp() throws IOException {
        set = EvalSet.load("harness", CASES);
        deli = outbound(set.rows().get(0).description());
        chat.reset().answering(EvalRunnerTest::scripted, 400, 50);
        embeddingModel.reset()
                .near(outbound(set.rows().get(4).description()), deli, 0.98)    // VELLICOTT: direct at 0.97
                .near(outbound(set.rows().get(2).description()), deli, 0.80);   // ORRISWICK: an example, not direct
    }

    @AfterEach
    void cleanUp() {
        chat.reset();
        embeddingModel.reset();
        for (Long account : jdbc.queryForList("SELECT id FROM account WHERE name = ?", Long.class,
                EvalSeeder.ACCOUNT_NAME)) {
            jdbc.update("DELETE FROM txn WHERE account_id = ?", account);
            jdbc.update("DELETE FROM statement WHERE account_id = ?", account);
            jdbc.update("DELETE FROM csv_mapping WHERE account_id = ?", account);
            jdbc.update("DELETE FROM account WHERE id = ?", account);
        }
        jdbc.update("DELETE FROM merchant_embedding");
    }

    private String outbound(String description) {
        return promptBuilder.outboundMerchant(cleaner.clean(description).merchant(), null, RedactionContext.EMPTY);
    }

    private static String scripted(Prompt prompt) {
        List<Map<String, Object>> answers = new ArrayList<>();
        JsonNode items = ModelAnswerReader.items(prompt);
        for (JsonNode item : items) {
            String merchant = item.get("merchant").asString();
            Object[] answer = ANSWERS.entrySet().stream().filter(entry -> merchant.contains(entry.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElseThrow();
            answers.add(Map.of("key", item.get("key").asString(), "category", answer[0], "confidence", answer[1]));
        }
        return JsonMapper.builder().build().writeValueAsString(Map.of("answers", answers));
    }

    private static VariantResult variant(EvalResult result, Variant variant) {
        return result.variants().stream().filter(v -> v.variant() == variant).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ variants

    @Test
    void eachVariantIsWiredAsDescribed() {
        EvalResult result = runner.run(set, INPUTS, PRICES);

        assertThat(result.variants()).extracting(VariantResult::variant)
                .containsExactly(Variant.A, Variant.B, Variant.C, Variant.D);
        assertThat(result.variants()).extracting(VariantResult::status).containsOnly("DONE");
        assertThat(chat.prompts()).as("one call each for B, C and D; A calls nothing").hasSize(3);

        // B: no embeddings, an empty examples block, every item sent.
        VariantResult b = variant(result, Variant.B);
        assertThat(b.usage().embeddingCalls()).isZero();
        assertThat(ModelAnswerReader.examples(chat.prompts().get(0))).isEmpty();
        assertThat(ModelAnswerReader.items(chat.prompts().get(0))).hasSize(4);
        assertThat(b.usage().itemsWithExamples()).isZero();
        assertThat(b.settings()).containsEntry("embeddings", "off").containsEntry("k", "0");

        // C: embeddings, the deli as the one example, no direct assignment.
        VariantResult c = variant(result, Variant.C);
        assertThat(c.usage().embeddingCalls()).isPositive();
        assertThat(ModelAnswerReader.examples(chat.prompts().get(1))).containsExactly(deli + "=Dining");
        assertThat(ModelAnswerReader.items(chat.prompts().get(1))).hasSize(4);
        assertThat(c.usage().itemsWithExamples()).isEqualTo(2);
        assertThat(c.usage().itemsDirect()).isZero();
        assertThat(c.settings()).containsEntry("direct-assignment", "false");

        // D: VELLICOTT takes the deli's category without a call; it pays for its own embeddings again.
        VariantResult d = variant(result, Variant.D);
        assertThat(d.usage().itemsDirect()).isOne();
        assertThat(d.usage().embeddingCalls()).isEqualTo(c.usage().embeddingCalls());
        assertThat(ModelAnswerReader.items(chat.prompts().get(2))).hasSize(3)
                .noneMatch(item -> item.get("merchant").asString().contains("VELLICOTT"));
        assertThat(d.settings()).containsEntry("direct-assignment", "true").containsEntry("direct-min-similarity",
                "0.97");

        assertThat(variant(result, Variant.A).usage().chatCalls()).isZero();
        assertThat(result.settings()).containsEntry("eval.min-confidence", "0.70")
                .containsEntry("finalysis.ai.examples.max-examples-per-batch", "50")
                .containsEntry("models.categorization", AiIntegrationTest.CATEGORIZATION_MODEL)
                .containsEntry("prompt.version", CategorizationPromptBuilder.PROMPT_VERSION)
                .containsKey("prompt.system-sha256");
    }

    @Test
    void theNumbersAreTheHandComputedOnes() {
        EvalResult result = runner.run(set, INPUTS, PRICES);

        // A: rows 1, 2, 8, 9 by rules, all right; 3-7 uncategorized.
        VariantMetrics a = variant(result, Variant.A).metrics();
        assertThat(List.of(a.categorized(), a.correct(), a.wrong(), a.review(), a.uncategorized()))
                .containsExactly(4, 4, 0, 0, 5);
        assertThat(a.coveragePct()).isEqualByComparingTo("44.4");
        assertThat(a.accuracyPct()).isEqualByComparingTo("100.0");

        // B and C: plus 3, 4, 5 right and 6 wrong; 7 (0.55) in review.
        for (Variant variant : List.of(Variant.B, Variant.C)) {
            VariantMetrics m = variant(result, variant).metrics();
            assertThat(List.of(m.categorized(), m.correct(), m.wrong(), m.review(), m.uncategorized()))
                    .as(variant.name()).containsExactly(8, 7, 1, 1, 0);
            assertThat(m.coveragePct()).isEqualByComparingTo("88.9");
            assertThat(m.accuracyPct()).isEqualByComparingTo("87.5");
            assertThat(m.overallPct()).isEqualByComparingTo("77.8");
            assertThat(m.bySource()).isEqualTo(Map.of(RowSource.RULE, new Counts(4, 4, 0),
                    RowSource.AI, new Counts(4, 3, 1)));
            // Bands: 3, 4, 5 at 0.92 (right); 6 at 0.80 (wrong); 7 at 0.55 (right, not applied).
            assertThat(m.answeredRows()).isEqualTo(5);
            assertThat(m.confidenceBands().get(9).rows()).isEqualTo(3);
            assertThat(m.confidenceBands().get(9).accuracyPct()).isEqualByComparingTo("100.0");
            assertThat(m.confidenceBands().get(8).accuracyPct()).isEqualByComparingTo("0.0");
            assertThat(m.confidenceBands().get(5).rows()).isOne();
        }

        // D: the same rows right and wrong, VELLICOTT now by direct assignment.
        VariantMetrics d = variant(result, Variant.D).metrics();
        assertThat(List.of(d.categorized(), d.correct(), d.wrong(), d.review())).containsExactly(8, 7, 1, 1);
        assertThat(d.bySource()).isEqualTo(Map.of(RowSource.RULE, new Counts(4, 4, 0),
                RowSource.AI, new Counts(3, 2, 1), RowSource.DIRECT, new Counts(1, 1, 0)));
        assertThat(d.answeredRows()).isEqualTo(4);
        assertThat(variant(result, Variant.D).rows().get(4).outcome()).isEqualTo("DIRECT");
    }

    @Test
    void usageAndCostComeFromTheRunsCounts() {
        EvalResult result = runner.run(set, INPUTS, PRICES);

        VariantResult b = variant(result, Variant.B);
        assertThat(b.usage().chatCalls()).isOne();
        assertThat(b.usage().inputTokens()).isEqualTo(400);
        assertThat(b.usage().outputTokens()).isEqualTo(50);
        assertThat(b.usage().estimatedCostUsd()).isEqualByComparingTo("0.000650");   // (400 * 1 + 50 * 5) / 1e6
        assertThat(b.usage().costIsLowerBound()).isFalse();
        VariantResult c = variant(result, Variant.C);
        assertThat(c.usage().embeddingInputTokens()).isPositive();
        assertThat(c.usage().estimatedCostUsd()).isGreaterThan(b.usage().estimatedCostUsd());
        assertThat(variant(result, Variant.A).usage().estimatedCostUsd()).isEqualByComparingTo("0");
        // Same calls, same reported usage per call: the fake's fixed counts make the measured share 0.
        assertThat(result.measuredExamplesSharePct()).isEqualByComparingTo("0.0");

        assertThat(c.examples().batches()).isOne();
        assertThat(c.examples().perBatch()).containsExactly(1);
        assertThat(c.examples().cap()).isEqualTo(50);
        assertThat(c.examples().estimatedSharePct()).isPositive();
        assertThat(b.examples().perBatch()).containsExactly(0);
        assertThat(b.examples().estimatedSharePct())
                .as("an empty block is still a few characters").isLessThan(c.examples().estimatedSharePct());
    }

    /**
     * Step 9c diagnostics: C and D scan the embeddings they left. The money-out items see the deli (RULE, Dining) as
     * eligible: VELLICOTT at 0.98, ORRISWICK at 0.80; BRISKWATER and MOSSGRAVE only at the fake's hashed, near-zero
     * similarities. The payroll (money in) and the Zelle payment (never embedded) are never eligible for them.
     */
    @Test
    void theNearestExampleOfEachItemIsScannedInCAndD() {
        EvalResult result = runner.run(set, INPUTS, PRICES);
        int calls = chat.prompts().size();

        assertThat(variant(result, Variant.A).neighbours()).isNull();
        assertThat(variant(result, Variant.B).neighbours()).as("B has no embeddings").isNull();
        for (Variant variant : List.of(Variant.C, Variant.D)) {
            ExampleDiagnostics n = variant(result, variant).neighbours();
            assertThat(n.floor()).as(variant.name()).isEqualByComparingTo("0.65");
            assertThat(n.items()).as("every item sent or assigned directly").isEqualTo(4);
            assertThat(n.withoutExample()).isZero();
            assertThat(nearest(n, "VELLICOTT").doubleValue()).isCloseTo(0.98, within(0.001));
            assertThat(nearest(n, "ORRISWICK").doubleValue()).isCloseTo(0.80, within(0.001));
            assertThat(nearest(n, "BRISKWATER").doubleValue()).isLessThan(0.5);
            assertThat(nearest(n, "MOSSGRAVE").doubleValue()).isLessThan(0.5);
            assertThat(n.perItem()).allSatisfy(item -> assertThat(item.direction()).isEqualTo(-1));
            assertThat(n.floorCounts()).extracting(FloorCount::items).containsExactly(2, 2, 2, 2);
            assertThat(n.floorCounts().getLast().items()).as("at the configured floor, what the app counted (in D, "
                    + "VELLICOTT is direct, not an item with examples)").isEqualTo(variant(result, variant).usage()
                    .itemsWithExamples() + variant(result, variant).usage().itemsDirect());
            assertThat(n.histogram().get(19).items()).as("VELLICOTT at 0.98").isOne();
            assertThat(n.histogram().get(16).items()).as("ORRISWICK at 0.8000 as reported").isOne();
        }
        assertThat(variant(result, Variant.D).neighbours().perItem())
                .filteredOn(item -> item.merchant().contains("VELLICOTT")).extracting(ItemNeighbours::outcome)
                .containsExactly("DIRECT");
        assertThat(chat.prompts()).as("the scan makes no call").hasSize(calls);
    }

    private static BigDecimal nearest(ExampleDiagnostics diagnostics, String word) {
        return diagnostics.perItem().stream().filter(item -> item.merchant().contains(word)).findFirst()
                .orElseThrow().nearestSimilarity();
    }

    @Test
    void anUnpricedModelHasNoCostRatherThanAGuess() {
        EvalResult result = runner.run(set, INPUTS, new EvalPrices("none", Map.of()));

        assertThat(variant(result, Variant.B).usage().estimatedCostUsd()).isNull();
    }

    // ------------------------------------------------------------------ sweep

    @Test
    void theSweepComesFromTheSavedConfidencesWithNoFurtherCall() {
        EvalResult result = runner.run(set, INPUTS, PRICES);
        int calls = chat.prompts().size();

        VariantResult b = variant(result, Variant.B);
        assertThat(variant(result, Variant.A).sweep()).isNull();
        assertThat(b.sweep()).hasSize(10);
        SweepPoint lowest = b.sweep().getFirst();
        assertThat(List.of(lowest.categorized(), lowest.correct(), lowest.review())).containsExactly(9, 8, 0);
        assertThat(b.sweep().get(4)).as("0.70, the run's own threshold").isEqualTo(EvalMetrics.at(b.rows(),
                new BigDecimal("0.70")));
        assertThat(b.sweep().get(4).categorized()).isEqualTo(b.metrics().categorized());
        assertThat(b.sweep().get(4).correct()).isEqualTo(b.metrics().correct());
        assertThat(b.sweep().getLast().categorized()).as("0.95: the 0.92 answers drop too").isEqualTo(4);
        assertThat(EvalMetrics.sweep(b.rows())).isEqualTo(b.sweep());
        assertThat(chat.prompts()).hasSize(calls);
    }

    @Test
    void withoutTheSweepFlagThereIsNoSweep() {
        EvalResult result = runner.run(set, new EvalInputs(new BigDecimal("0.70"), 0.97, false), PRICES);

        assertThat(result.variants()).allSatisfy(variant -> assertThat(variant.sweep()).isNull());
    }

    // ------------------------------------------------------------------ ground truth

    /**
     * Labels are read for scoring only: seeding imports descriptions and amounts, no USER category ever exists, and
     * every example sent is a merchant a seed rule categorized. ORRISWICK (expected Groceries) sits at about 0.78
     * from VELLICOTT: had its label been loaded, it would have been VELLICOTT's example.
     */
    @Test
    void examplesNeverComeFromTheGroundTruth() {
        assertThat(Arrays.stream(EvalSet.StatementLine.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("number", "date", "description", "amount");

        runner.run(set, INPUTS, PRICES);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM txn WHERE category_source = 'USER'", Integer.class))
                .isZero();
        Set<String> ruleMerchants = new HashSet<>();
        jdbc.queryForList("SELECT DISTINCT merchant FROM txn WHERE category_source = 'RULE'", String.class)
                .forEach(merchant -> ruleMerchants.add(promptBuilder.outboundMerchant(merchant, null,
                        RedactionContext.EMPTY)));
        List<String> sent = chat.prompts().stream().flatMap(prompt -> ModelAnswerReader.examples(prompt).stream())
                .toList();
        assertThat(sent).isNotEmpty().allSatisfy(example ->
                assertThat(ruleMerchants).contains(example.substring(0, example.lastIndexOf('='))));
        assertThat(sent).noneMatch(example -> example.contains("ORRISWICK") || example.contains("MOSSGRAVE")
                || example.contains("BRISKWATER") || example.contains("VELLICOTT"));
    }

    @Test
    void seedingNeedsADatabaseWithoutTxns() {
        runner.run(set, INPUTS, PRICES);

        assertThatThrownBy(() -> runner.run(set, INPUTS, PRICES))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no txns");
    }

    // ------------------------------------------------------------------ files

    @Test
    void theResultsAreWrittenAsJsonAndMarkdown(@TempDir Path dir) throws IOException {
        EvalResult result = runner.run(set, INPUTS, PRICES);
        EvalReport report = EvalReport.of(new EvalGuards.GitState("0123456789abcdef0123456789abcdef01234567", false),
                Instant.parse("2026-10-15T12:34:56Z"), result);

        List<Path> files = report.write(dir);

        assertThat(files).extracting(file -> file.getFileName().toString()).containsExactly(
                "harness-0123456789ab-20261015T123456Z.json", "harness-0123456789ab-20261015T123456Z.md");
        JsonNode written = json.readTree(Files.readString(files.get(0)));
        assertThat(written.get("commit").asString()).isEqualTo("0123456789abcdef0123456789abcdef01234567");
        assertThat(written.get("dirty").asBoolean()).isFalse();
        assertThat(written.get("result").get("settings").get("eval.direct-min-similarity").asString())
                .isEqualTo("0.97");
        assertThat(written.get("result").get("variants")).hasSize(4);
        assertThat(written.get("result").get("variants").get(1).get("rows")).hasSize(9);
        JsonNode neighbours = written.get("result").get("variants").get(2).get("neighbours");
        assertThat(neighbours.get("perItem")).hasSize(4);
        assertThat(neighbours.get("perItem").get(0).has("nearestSimilarity")).isTrue();
        assertThat(neighbours.get("perItem").get(0).has("examplesAboveFloor")).isTrue();
        assertThat(neighbours.get("floorCounts")).hasSize(4);
        assertThat(written.get("result").get("variants").get(1).get("neighbours").isNull()).isTrue();
        assertThat(written.get("result").get("variants").get(1).get("rows").get(6).get("confidence").decimalValue())
                .as("MOSSGRAVE's confidence is saved although it wasn't applied").isEqualByComparingTo("0.55");

        String markdown = Files.readString(files.get(1));
        assertThat(markdown).contains("# Categorization eval: harness", "## Summary", "## Confidence",
                "## Threshold sweep", "## Examples", "## Nearest examples", "| 0.65-0.70 |", "| 0.50 | 2 | 2 |",
                "Diagnostics of the examples feature, not accuracy", "## Settings", "B: rules + AI, no examples",
                "D: rules + AI + examples + direct assignment", "0.0007");
        assertThat(report.consoleSummary()).contains("| Variant | Status | Coverage", "Sweep, B");
        assertThatThrownBy(() -> report.write(dir)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    /** Reads what a prompt sent, the way a model would see it. */
    static final class ModelAnswerReader {

        private static final JsonMapper JSON = JsonMapper.builder().build();

        private ModelAnswerReader() {
        }

        static JsonNode items(Prompt prompt) {
            return block(prompt, CategorizationPromptBuilder.OPEN_TAG, CategorizationPromptBuilder.CLOSE_TAG)
                    .get("items");
        }

        /** Every example sent, as {@code merchant=category}. */
        static List<String> examples(Prompt prompt) {
            List<String> examples = new ArrayList<>();
            block(prompt, CategorizationPromptBuilder.EXAMPLES_OPEN_TAG, CategorizationPromptBuilder.EXAMPLES_CLOSE_TAG)
                    .get("examples").forEach(example -> examples.add(example.get("merchant").asString() + "="
                            + example.get("category").asString()));
            return examples;
        }

        private static JsonNode block(Prompt prompt, String open, String close) {
            String user = prompt.getInstructions().get(1).getText();
            return JSON.readTree(user.substring(user.indexOf(open) + open.length(), user.lastIndexOf(close)));
        }
    }
}
