package com.finalysis.categorize.eval;

import com.finalysis.ai.AiProperties;
import com.finalysis.ai.TextEmbedder;
import com.finalysis.categorize.AiCategorizationProperties;
import com.finalysis.categorize.AiCategorizer;
import com.finalysis.categorize.AiCategorizer.AiRunResult;
import com.finalysis.categorize.AiRunObserver.ItemDecision;
import com.finalysis.categorize.CategorizationLock;
import com.finalysis.categorize.CategorizationModelClient;
import com.finalysis.categorize.CategorizationPromptBuilder;
import com.finalysis.categorize.CategorizationService;
import com.finalysis.categorize.MerchantEmbeddingRepository;
import com.finalysis.categorize.MerchantEmbeddingStage;
import com.finalysis.categorize.RedactionContext;
import com.finalysis.categorize.RedactionContextQuery;
import com.finalysis.categorize.SimilarMerchantProperties;
import com.finalysis.categorize.eval.EvalMetrics.RowSource;
import com.finalysis.categorize.eval.EvalMetrics.ScoredRow;
import com.finalysis.categorize.eval.EvalMetrics.SweepPoint;
import com.finalysis.categorize.eval.EvalMetrics.VariantMetrics;
import com.finalysis.categorize.eval.EvalRecorder.Batch;
import com.finalysis.categorize.eval.EvalSeeder.Seeded;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Runs the four variants of the accuracy evaluation on one seeded database and scores each (CAT-05;
 * docs/plans/phase-4.md, step 9a notes). The live run ({@code CategorizationLiveEvalIT}) and the harness's own tests
 * (fake models) use this same code.
 *
 * <ol>
 *   <li><b>A</b> rules only: what seeding left (import, cleaning, seed rules); its time is a second rules pass,
 *       which must change nothing;</li>
 *   <li><b>B</b> rules + AI, no examples: no embedding call, an empty examples block;</li>
 *   <li><b>C</b> rules + AI + examples: the context's example settings, direct assignment off;</li>
 *   <li><b>D</b> C plus direct assignment at the run's {@code direct-min-similarity}.</li>
 * </ol>
 * Before B, C and D the AI's work is undone (AI categories, review marks, embeddings), and the database must then be
 * exactly as after A, so each variant starts from the same state and pays for its own embeddings. Each AI variant
 * runs {@link AiCategorizer} as built by the app, with the run's {@code min-confidence}; an {@link EvalRecorder} keeps
 * every answer's confidence, so the sweep needs no further call.
 *
 * <p>Imported into a test context with {@code @Import}; never component-scanned.
 */
public class EvalRunner {

    /** The variants, in the order they run and are reported. */
    enum Variant {
        A("rules only"),
        B("rules + AI, no examples"),
        C("rules + AI + examples"),
        D("rules + AI + examples + direct assignment");

        final String description;

        Variant(String description) {
            this.description = description;
        }
    }

    /**
     * The run's inputs.
     *
     * @param sweep whether to compute the threshold sweep (dev runs only)
     */
    record EvalInputs(BigDecimal minConfidence, double directMinSimilarity, boolean sweep) {
    }

    /**
     * Calls and tokens of one variant, from the run's own counts.
     *
     * @param estimatedCostUsd null when a model has no price in {@link EvalPrices}
     * @param costIsLowerBound some call reported no usage, so its tokens are missing from the estimate
     */
    record Usage(int chatCalls, int retries, long inputTokens, long outputTokens, int chatCallsWithoutUsage,
                 int embeddingCalls, long embeddingInputTokens, int embeddingCallsWithoutUsage, int itemsSelected,
                 int itemsSent, int itemsWithExamples, int itemsDirect, BigDecimal estimatedCostUsd,
                 boolean costIsLowerBound) {

        static Usage none() {
            return new Usage(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO.setScale(6), false);
        }
    }

    /**
     * The examples blocks of one variant.
     *
     * @param perBatch           distinct examples in each batch, in order
     * @param estimatedSharePct  the blocks' share of everything sent, by characters, each batch weighted by its calls:
     *                           an estimate of their share of input tokens (null without a batch)
     */
    record ExamplesStats(int cap, int batches, List<Integer> perBatch, Integer min, BigDecimal mean, Integer max,
                         BigDecimal estimatedSharePct) {
    }

    record VariantResult(Variant variant, String description, String status, Map<String, String> settings,
                         long wallMillis, VariantMetrics metrics, List<SweepPoint> sweep, Usage usage,
                         ExamplesStats examples, List<ScoredRow> rows) {
    }

    /**
     * @param measuredExamplesSharePct (C's input tokens - B's) / C's: the examples' measured share, when both reported
     *                                 usage for every call and made the same number of calls; null otherwise
     */
    record EvalResult(String set, String setSha256, int rows, EvalInputs inputs, Map<String, String> settings,
                      String pricesSource, long seedMillis, List<VariantResult> variants,
                      BigDecimal measuredExamplesSharePct) {
    }

    /** Settings read from the environment as configured; nothing that names credentials or a profile. */
    static final List<String> ENVIRONMENT_SETTINGS = List.of(
            "finalysis.ai.mode", "finalysis.ai.provider", "finalysis.ai.roles.categorization",
            "finalysis.ai.roles.embedding", "finalysis.ai.native-structured-output",
            "finalysis.ai.embedding.dimensions",
            "spring.ai.model.chat", "spring.ai.model.embedding", "spring.ai.bedrock.aws.region",
            "spring.ai.bedrock.aws.timeout", "spring.ai.bedrock.converse.chat.options.model",
            "spring.ai.bedrock.converse.chat.options.temperature", "spring.ai.bedrock.converse.chat.options.max-tokens",
            "spring.ai.bedrock.titan.embedding.model", "spring.ai.bedrock.titan.embedding.input-type");

    private final AiProperties ai;
    private final AiCategorizationProperties categorization;
    private final SimilarMerchantProperties examples;
    private final ObjectProvider<ChatModel> chatModels;
    private final CategorizationModelClient client;
    private final CategorizationPromptBuilder promptBuilder;
    private final TextEmbedder embedder;
    private final MerchantEmbeddingRepository embeddings;
    private final RedactionContextQuery redactionContext;
    private final CategorizationLock lock;
    private final CategorizationService rules;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final EvalSeeder seeder;
    private final Environment environment;

    public EvalRunner(AiProperties ai, AiCategorizationProperties categorization, SimilarMerchantProperties examples,
                      ObjectProvider<ChatModel> chatModels, CategorizationModelClient client,
                      CategorizationPromptBuilder promptBuilder, TextEmbedder embedder,
                      MerchantEmbeddingRepository embeddings, RedactionContextQuery redactionContext,
                      CategorizationLock lock, CategorizationService rules, JdbcTemplate jdbc,
                      PlatformTransactionManager transactions, EvalSeeder seeder, Environment environment) {
        this.ai = ai;
        this.categorization = categorization;
        this.examples = examples;
        this.chatModels = chatModels;
        this.client = client;
        this.promptBuilder = promptBuilder;
        this.embedder = embedder;
        this.embeddings = embeddings;
        this.redactionContext = redactionContext;
        this.lock = lock;
        this.rules = rules;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.seeder = seeder;
        this.environment = environment;
    }

    public EvalResult run(EvalSet set, EvalInputs inputs, EvalPrices prices) {
        if (!ai.enabled() || chatModels.getIfAvailable() == null || !embedder.enabled()) {
            throw new IllegalStateException("The evaluation needs AI on, with a chat and an embedding model");
        }
        if (categorization.runAfterImport()) {
            throw new IllegalStateException("run-after-import must be off: seeding would start AI runs by itself");
        }
        Seeded seeded = seeder.seed(set.statementLines());

        List<VariantResult> variants = new ArrayList<>();
        Map<Long, String> afterRules = snapshot(seeded.accountId());
        long start = System.nanoTime();
        rules.categorizeAll();
        long rulesMillis = millisSince(start);
        requireState(seeded.accountId(), afterRules, "A second rules pass changed a txn");
        List<ScoredRow> rulesOnly = score(set, seeded, new EvalRecorder());
        variants.add(new VariantResult(Variant.A, Variant.A.description, "DONE", Map.of(), rulesMillis,
                EvalMetrics.of(rulesOnly), null, Usage.none(), examplesStats(new EvalRecorder(), 0), rulesOnly));

        for (Variant variant : List.of(Variant.B, Variant.C, Variant.D)) {
            undoAi();
            requireState(seeded.accountId(), afterRules, "Undoing the AI did not restore the state after rules");
            AiCategorizationProperties settings = new AiCategorizationProperties(categorization.batchSize(),
                    categorization.maxItemsPerRun(), inputs.minConfidence(), false);
            SimilarMerchantProperties exampleSettings = exampleSettings(variant, inputs);
            TextEmbedder variantEmbedder = variant == Variant.B ? new DisabledEmbedder(embedder) : embedder;
            AiCategorizer categorizer = new AiCategorizer(ai, settings, chatModels, client, promptBuilder,
                    new MerchantEmbeddingStage(ai, variantEmbedder, embeddings, promptBuilder, exampleSettings, jdbc),
                    exampleSettings, redactionContext, lock, jdbc, transactions);

            EvalRecorder recorder = new EvalRecorder();
            start = System.nanoTime();
            AiRunResult result = categorizer.run(false, recorder);
            long millis = millisSince(start);

            List<ScoredRow> rows = score(set, seeded, recorder);
            variants.add(new VariantResult(variant, variant.description, result.status().name(),
                    variantSettings(settings, exampleSettings, variant), millis, EvalMetrics.of(rows),
                    inputs.sweep() ? EvalMetrics.sweep(rows) : null, usage(result, prices),
                    examplesStats(recorder, exampleSettings.maxExamplesPerBatch()), rows));
        }
        return new EvalResult(set.name(), set.sha256(), set.rows().size(), inputs, settings(inputs), prices.source(),
                seeded.millis(), List.copyOf(variants),
                measuredShare(variants.get(1).usage(), variants.get(2).usage()));
    }

    private SimilarMerchantProperties exampleSettings(Variant variant, EvalInputs inputs) {
        return switch (variant) {
            case B -> new SimilarMerchantProperties(0, examples.minSimilarity(), examples.maxEmbeddingsPerRun(), false,
                    examples.directMinSimilarity(), examples.maxExamplesPerBatch());
            case C -> new SimilarMerchantProperties(examples.k(), examples.minSimilarity(),
                    examples.maxEmbeddingsPerRun(), false, examples.directMinSimilarity(),
                    examples.maxExamplesPerBatch());
            case D -> new SimilarMerchantProperties(examples.k(), examples.minSimilarity(),
                    examples.maxEmbeddingsPerRun(), true, inputs.directMinSimilarity(), examples.maxExamplesPerBatch());
            case A -> throw new IllegalArgumentException("A runs no AI");
        };
    }

    /** Each row as the database has it after the variant, with the AI's answer (applied or not) beside it. */
    private List<ScoredRow> score(EvalSet set, Seeded seeded, EvalRecorder recorder) {
        Map<Long, Integer> rowByTxn = new HashMap<>();
        seeded.txnByRow().forEach((row, txn) -> rowByTxn.put(txn, row));
        Map<Integer, ScoredRow> scored = new TreeMap<>();
        jdbc.query("""
                SELECT t.id, c.name, t.category_source, t.category_prompt_version, t.ai_review_reason, t.is_transfer
                FROM txn t LEFT JOIN category c ON c.id = t.category_id
                WHERE t.account_id = ?""", rs -> {
            long txn = rs.getLong("id");
            int number = rowByTxn.get(txn);
            String predicted = rs.getString("name");
            if (predicted == null && rs.getBoolean("is_transfer")) {
                predicted = "Transfer";   // a linked txn with no category counts as Transfer (§1.1)
            }
            String source = rs.getString("category_source");
            RowSource rowSource = source == null ? RowSource.NONE
                    : !"AI".equals(source) ? RowSource.valueOf(source)
                    : rs.getString("category_prompt_version") == null ? RowSource.DIRECT : RowSource.AI;
            ItemDecision decision = recorder.decision(txn);
            scored.put(number, new ScoredRow(number, set.rows().get(number - 1).description(), set.expected(number),
                    predicted, rowSource, rs.getString("ai_review_reason"),
                    decision == null ? null : decision.outcome().name(),
                    decision == null ? null : decision.category(), decision == null ? null : decision.confidence()));
        }, seeded.accountId());
        return List.copyOf(scored.values());
    }

    private Usage usage(AiRunResult result, EvalPrices prices) {
        int withoutUsage = result.callsWithoutUsage() + result.embeddings().callsWithoutUsage();
        return new Usage(result.calls(), result.retries(), result.inputTokens(), result.outputTokens(),
                result.callsWithoutUsage(), result.embeddings().calls(), result.embeddings().inputTokens(),
                result.embeddings().callsWithoutUsage(), result.itemsSelected(), result.itemsSent(),
                result.itemsWithExamples(), result.itemsDirect(),
                prices.cost(ai.roles().categorization(), result.inputTokens(), result.outputTokens(),
                        embedder.modelId(), result.embeddings().inputTokens()),
                withoutUsage > 0);
    }

    private static ExamplesStats examplesStats(EvalRecorder recorder, int cap) {
        List<Batch> batches = recorder.batches();
        List<Integer> perBatch = batches.stream().map(Batch::examples).toList();
        if (batches.isEmpty()) {
            return new ExamplesStats(cap, 0, perBatch, null, null, null, null);
        }
        long examplesChars = 0;
        long allChars = 0;
        for (Batch batch : batches) {
            examplesChars += (long) batch.examplesBlockChars() * batch.attempts().size();
            allChars += (long) (batch.systemChars() + batch.userChars()) * batch.attempts().size();
        }
        int total = perBatch.stream().mapToInt(Integer::intValue).sum();
        return new ExamplesStats(cap, batches.size(), perBatch,
                perBatch.stream().mapToInt(Integer::intValue).min().orElseThrow(),
                BigDecimal.valueOf(total).divide(BigDecimal.valueOf(batches.size()), 2, RoundingMode.HALF_UP),
                perBatch.stream().mapToInt(Integer::intValue).max().orElseThrow(), pct(examplesChars, allChars));
    }

    static BigDecimal measuredShare(Usage withoutExamples, Usage withExamples) {
        if (withoutExamples.chatCallsWithoutUsage() > 0 || withExamples.chatCallsWithoutUsage() > 0
                || withoutExamples.chatCalls() != withExamples.chatCalls() || withExamples.inputTokens() == 0) {
            return null;
        }
        return pct(withExamples.inputTokens() - withoutExamples.inputTokens(), withExamples.inputTokens());
    }

    private static BigDecimal pct(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part * 100).divide(BigDecimal.valueOf(whole), 1,
                RoundingMode.HALF_UP);
    }

    /** Every AI setting the run used: as configured, then the run's own inputs. */
    private Map<String, String> settings(EvalInputs inputs) {
        Map<String, String> settings = new TreeMap<>();
        for (String key : ENVIRONMENT_SETTINGS) {
            String value = environment.getProperty(key);
            if (value != null) {
                settings.put(key, value);
            }
        }
        settings.put("finalysis.ai.categorization.batch-size", String.valueOf(categorization.batchSize()));
        settings.put("finalysis.ai.categorization.max-items-per-run", String.valueOf(categorization.maxItemsPerRun()));
        settings.put("finalysis.ai.categorization.min-confidence (configured)",
                categorization.minConfidence().toPlainString());
        settings.put("finalysis.ai.examples.k", String.valueOf(examples.k()));
        settings.put("finalysis.ai.examples.min-similarity", String.valueOf(examples.minSimilarity()));
        settings.put("finalysis.ai.examples.max-embeddings-per-run", String.valueOf(examples.maxEmbeddingsPerRun()));
        settings.put("finalysis.ai.examples.max-examples-per-batch", String.valueOf(examples.maxExamplesPerBatch()));
        settings.put("finalysis.ai.examples.direct-min-similarity (configured)",
                String.valueOf(examples.directMinSimilarity()));
        settings.put("models.categorization", ai.roles().categorization());
        settings.put("models.embedding", embedder.modelId());
        settings.put("prompt.version", CategorizationPromptBuilder.PROMPT_VERSION);
        settings.put("prompt.system-sha256", EvalSet.sha256(promptBuilder.build(List.of(), List.of(),
                RedactionContext.EMPTY).system().getBytes(StandardCharsets.UTF_8)));
        settings.put("eval.min-confidence", inputs.minConfidence().toPlainString());
        settings.put("eval.direct-min-similarity", String.valueOf(inputs.directMinSimilarity()));
        return settings;
    }

    private static Map<String, String> variantSettings(AiCategorizationProperties settings,
                                                       SimilarMerchantProperties examples, Variant variant) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("min-confidence", settings.minConfidence().toPlainString());
        values.put("embeddings", variant == Variant.B ? "off" : "on");
        values.put("k", String.valueOf(examples.k()));
        values.put("max-examples-per-batch", String.valueOf(examples.maxExamplesPerBatch()));
        values.put("direct-assignment", String.valueOf(examples.directAssignment()));
        if (examples.directAssignment()) {
            values.put("direct-min-similarity", String.valueOf(examples.directMinSimilarity()));
        }
        return values;
    }

    /** Removes what stages 4 and 5 wrote: AI categories, review marks and embeddings. Rule categories stay. */
    private void undoAi() {
        jdbc.update("""
                UPDATE txn
                SET category_id = NULL, category_source = NULL, category_rule_id = NULL, categorized_at = NULL,
                    category_model = NULL, category_confidence = NULL, category_prompt_version = NULL,
                    ai_review_reason = NULL, ai_review_at = NULL
                WHERE category_source = 'AI' OR ai_review_reason IS NOT NULL""");
        jdbc.update("DELETE FROM merchant_embedding");
    }

    /** Each txn's category, source, rule and review mark. */
    private Map<Long, String> snapshot(long accountId) {
        Map<Long, String> state = new TreeMap<>();
        jdbc.query("""
                SELECT id, category_id, category_source, category_rule_id, ai_review_reason FROM txn
                WHERE account_id = ?""", rs -> {
            state.put(rs.getLong("id"), rs.getString("category_id") + "|" + rs.getString("category_source") + "|"
                    + rs.getString("category_rule_id") + "|" + rs.getString("ai_review_reason"));
        }, accountId);
        return state;
    }

    private void requireState(long accountId, Map<Long, String> expected, String message) {
        if (!snapshot(accountId).equals(expected)) {
            throw new IllegalStateException(message);
        }
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** Variant B: the context's embedder, switched off, so stage 4 makes no call and no item has examples. */
    private record DisabledEmbedder(TextEmbedder delegate) implements TextEmbedder {

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public String modelId() {
            return delegate.modelId();
        }

        @Override
        public int dimensions() {
            return delegate.dimensions();
        }

        @Override
        public Embedding embed(String text) {
            throw new IllegalStateException("Variant B makes no embedding call");
        }
    }
}
