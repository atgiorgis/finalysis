package com.finalysis.categorize;

import com.finalysis.ai.AiProperties;
import com.finalysis.categorize.AnswerValidator.Accepted;
import com.finalysis.categorize.AnswerValidator.Validation;
import com.finalysis.categorize.CategorizationModelClient.Result;
import com.finalysis.categorize.CategorizationPromptBuilder.Candidate;
import com.finalysis.categorize.CategorizationPromptBuilder.CategorizationPrompt;
import com.finalysis.categorize.CategorizationPromptBuilder.LocalExample;
import com.finalysis.categorize.CategorizationPromptBuilder.Skip;
import com.finalysis.categorize.MerchantEmbeddingStage.EmbeddingRunResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stage 5 of the pipeline (CAT-02, docs/plans/phase-4.md §1.1, §4): the AI categorizes what the rules and merchant
 * memory left uncategorized.
 *
 * <p><b>What it sees.</b> Uncategorized txns with a merchant that are on no transfer link, one item per distinct
 * merchant and direction (§4.1). A txn the user categorized is never selected; neither is a RULE txn. AI txns and
 * txns already left for review are skipped too, so a second run makes no call, unless {@code force} asks again for
 * them (AI txns may then get a new AI answer; USER and RULE txns still never change). Every batch goes through
 * {@link CategorizationPromptBuilder}: cleaned merchant, {@link Redactor}, {@link OutboundPayloadGuard}, per-batch
 * keys ({@code i1}…) and the delimited, escaped JSON. No txn id leaves the machine; the key-to-txn mapping stays here.
 *
 * <p><b>Examples (stage 4, CAT-03).</b> Before the first batch, {@link MerchantEmbeddingStage} embeds what the items
 * and the trusted examples need, and each item gets up to {@code k} similar merchants that the user's rules or
 * corrections categorized. They go in the prompt's own {@code <examples>} block, as data. With
 * {@code finalysis.ai.examples.direct-assignment} on (off by default), an item whose nearest example is at least
 * {@code direct-min-similarity} similar takes that example's category without a chat call.
 *
 * <p><b>What it accepts.</b> {@link AnswerValidator} checks every answer. An answer that is invalid as a whole (it
 * doesn't parse, or a key is unknown, duplicated or missing) is asked again once, with the same batch; if the second
 * is invalid too, the batch's txns go to the review queue ({@code INVALID_ANSWER}). In a usable answer, an item with
 * an unknown category or a confidence outside 0 to 1 is rejected alone: its txns go to the review queue
 * ({@code INVALID_ANSWER}) and the rest of the batch is applied. A valid answer below {@code min-confidence} is not
 * applied either ({@code LOW_CONFIDENCE}). Nothing is guessed: those txns stay uncategorized. Transport retries
 * (throttling, 5xx) are the AWS SDK's own, set on the Bedrock client ({@code BedrockClientConfiguration}); a call that
 * still fails stops the run, and its txns are left as they were for the next run.
 *
 * <p><b>Writes.</b> Candidates are read in one short transaction; the model is called with no transaction open; each
 * batch's answers are written in a transaction of their own under the {@link CategorizationLock}, with SQL that
 * re-checks the txn: still uncategorized (or still AI, when forced), same merchant, not linked. So a correction or
 * a rule that lands while a call is in flight wins. An applied answer saves {@code source=AI}, the model id, the
 * confidence and {@link CategorizationPromptBuilder#PROMPT_VERSION}.
 *
 * <p><b>Cost guard.</b> At most {@code batch-size} items per call and {@code max-items-per-run} items per run; when
 * the cap is reached the run stops after a whole batch and reports what remains.
 *
 * <p>Runs never overlap (one at a time in this JVM). Logs counts and exception class names only, never a merchant,
 * a category or the model's output.
 */
@Service
public class AiCategorizer {

    private static final Logger log = LoggerFactory.getLogger(AiCategorizer.class);
    static final int ATTEMPTS = 2;

    /** Writes an AI category, re-checking the txn: uncategorized (or AI, when forced), same merchant, unlinked. */
    private static final String APPLY_SQL = """
            UPDATE txn
            SET category_id = ?, category_source = 'AI', category_rule_id = NULL, categorized_at = now(),
                category_model = ?, category_confidence = ?, category_prompt_version = ?,
                ai_review_reason = NULL, ai_review_at = NULL
            WHERE id = ?
              AND merchant = ?
              AND NOT is_transfer
              AND (category_source IS NULL OR (? AND category_source = 'AI'))""";

    private final AiProperties ai;
    private final AiCategorizationProperties properties;
    private final ObjectProvider<ChatModel> chatModel;
    private final CategorizationModelClient client;
    private final CategorizationPromptBuilder promptBuilder;
    private final MerchantEmbeddingStage embeddingStage;
    private final SimilarMerchantProperties examples;
    private final RedactionContextQuery redactionContext;
    private final CategorizationLock lock;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate readWrite;
    private final ReentrantLock running = new ReentrantLock();

    public AiCategorizer(AiProperties ai, AiCategorizationProperties properties, ObjectProvider<ChatModel> chatModel,
                         CategorizationModelClient client, CategorizationPromptBuilder promptBuilder,
                         MerchantEmbeddingStage embeddingStage, SimilarMerchantProperties examples,
                         RedactionContextQuery redactionContext, CategorizationLock lock, JdbcTemplate jdbc,
                         PlatformTransactionManager transactions) {
        this.ai = ai;
        this.properties = properties;
        this.chatModel = chatModel;
        this.client = client;
        this.promptBuilder = promptBuilder;
        this.embeddingStage = embeddingStage;
        this.examples = examples;
        this.redactionContext = redactionContext;
        this.lock = lock;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.readWrite = new TransactionTemplate(transactions);
    }

    /** How a run ended. */
    public enum Status {
        /** AI is off: nothing was read or sent. */
        OFF,
        /** Every selected item was handled. */
        DONE,
        /** {@code max-items-per-run} was reached; {@code itemsRemaining} wait for the next run. */
        CAP_REACHED,
        /** A call failed after the SDK's retries; that batch and the rest wait for the next run. */
        STOPPED_ON_ERROR
    }

    /**
     * Counts from one run; numbers only, so safe to log and return.
     *
     * @param itemsSelected      distinct merchants and directions the run found to categorize
     * @param itemsSent          items sent to the model (each counted once, however many attempts)
     * @param txnsCategorized    txns given an AI category (direct assignments included)
     * @param itemsLowConfidence items answered below {@code min-confidence}; their txns are left for review
     * @param itemsInvalid       items rejected alone, or in batches answered invalidly twice; their txns are left for
     *                           review
     * @param personToPerson     items never sent: a person-to-person payment (§5.2)
     * @param redactionBlocked   items never sent: redaction left nothing, or the guard refused (§5.3)
     * @param calls              model calls, retries included
     * @param retries            calls that repeated a batch after an invalid answer
     * @param callsWithoutUsage  calls whose provider reported no token usage; their tokens aren't in the totals
     * @param inputTokens        input tokens of the calls that reported usage
     * @param outputTokens       output tokens of the calls that reported usage
     * @param itemsRemaining     items not reached because of the cap or a failure
     * @param txnsRemaining      their txns
     * @param itemsWithExamples  sent items that had at least one example
     * @param itemsDirect        items given their nearest example's category without a call (direct assignment;
     *                           0 while it is off)
     * @param embeddings         stage 4's counts
     */
    public record AiRunResult(Status status, int itemsSelected, int itemsSent, int txnsCategorized,
                              int itemsLowConfidence, int itemsInvalid, int personToPerson, int redactionBlocked,
                              int calls, int retries, int callsWithoutUsage, long inputTokens, long outputTokens,
                              int itemsRemaining, int txnsRemaining, int itemsWithExamples, int itemsDirect,
                              EmbeddingRunResult embeddings) {

        static AiRunResult off() {
            return new AiRunResult(Status.OFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    EmbeddingRunResult.off());
        }
    }

    /**
     * One merchant and direction: the item the model sees, and the txns its answer is written to.
     *
     * @param latestAmount the newest txn's amount, as stored
     * @param direction    the sign of its amounts: -1 money out, 1 money in
     * @param counterparty any of its txns' person-to-person counterparty; never sent, it makes the item skipped
     */
    private record Group(String merchant, int direction, BigDecimal latestAmount, String counterparty,
                         List<Long> txnIds) {
    }

    private record Selection(List<Group> groups, List<String> categories, Map<String, Long> categoryIds,
                             RedactionContext context) {
    }

    /** Why a txn is left for review; the values of ck_txn_ai_review_reason. */
    public enum ReviewReason {
        LOW_CONFIDENCE, INVALID_ANSWER
    }

    /** Mutable tallies for one run. */
    private static final class Tally {
        int sent;
        int categorized;
        int lowConfidence;
        int invalid;
        int personToPerson;
        int redactionBlocked;
        int calls;
        int retries;
        int callsWithoutUsage;
        long inputTokens;
        long outputTokens;
        int withExamples;
        int direct;

        void count(Result result) {
            calls++;
            if (result.usageReported()) {
                inputTokens += result.inputTokens();
                outputTokens += result.outputTokens();
            } else {
                callsWithoutUsage++;
            }
        }
    }

    /**
     * Runs stage 5 now, on the calling thread; waits for a run already in progress to finish first.
     *
     * @param force also ask again for txns the AI already categorized or left for review
     */
    public AiRunResult run(boolean force) {
        if (!ai.enabled() || chatModel.getIfAvailable() == null) {
            return AiRunResult.off();
        }
        running.lock();
        try {
            AiRunResult result = categorize(force);
            log.info("AI categorization: status={} items={} sent={} txnsCategorized={} lowConfidence={} invalid={} "
                            + "personToPerson={} redactionBlocked={} calls={} retries={} callsWithoutUsage={} "
                            + "inputTokens={} outputTokens={} itemsRemaining={} txnsRemaining={} withExamples={} "
                            + "direct={} embeddings={}",
                    result.status(), result.itemsSelected(), result.itemsSent(), result.txnsCategorized(),
                    result.itemsLowConfidence(), result.itemsInvalid(), result.personToPerson(),
                    result.redactionBlocked(), result.calls(), result.retries(), result.callsWithoutUsage(),
                    result.inputTokens(), result.outputTokens(), result.itemsRemaining(), result.txnsRemaining(),
                    result.itemsWithExamples(), result.itemsDirect(), result.embeddings().status());
            return result;
        } finally {
            running.unlock();
        }
    }

    private AiRunResult categorize(boolean force) {
        Selection selection = readOnly.execute(status -> select(force));
        Set<String> allowed = Set.copyOf(selection.categories());
        Tally tally = new Tally();

        // Stage 4: vectors, then each item's examples. No transaction is open during the calls.
        MerchantEmbeddingStage.Outcome embedded = embeddingStage.embed(selection.groups().stream()
                .map(group -> new MerchantEmbeddingStage.Need(group.merchant(), group.counterparty())).toList(),
                selection.context());
        Map<Group, List<SimilarMerchant>> similar = new HashMap<>();
        for (Group group : selection.groups()) {
            similar.put(group, embeddingStage.examples(group.merchant(), group.direction(),
                    embedded.vectors().get(group.merchant())));
        }
        List<Group> groups = selection.groups();
        if (examples.directAssignment()) {
            List<Group> direct = groups.stream().filter(group -> isDirect(similar.get(group))).toList();
            assignDirectly(direct, similar, selection.categoryIds(), force, tally);
            Set<Group> assigned = Set.copyOf(direct);
            groups = groups.stream().filter(group -> !assigned.contains(group)).toList();
        }

        Status status = Status.DONE;
        int next = 0;
        while (next < groups.size()) {
            int room = properties.maxItemsPerRun() - tally.sent;
            if (room <= 0) {
                status = Status.CAP_REACHED;
                break;
            }
            List<Group> batch = groups.subList(next, Math.min(next + Math.min(properties.batchSize(), room),
                    groups.size()));
            CategorizationPrompt prompt = promptBuilder.build(selection.categories(), candidates(batch, similar),
                    selection.context());
            for (Skip skip : prompt.skipped().values()) {
                if (skip == Skip.PERSON_TO_PERSON) {
                    tally.personToPerson++;
                } else {
                    tally.redactionBlocked++;
                }
            }
            if (!prompt.isEmpty()) {
                Validation answer;
                try {
                    answer = ask(prompt, allowed, tally);
                } catch (RuntimeException e) {
                    // The SDK has already retried what it could; the message can echo the request, so class only.
                    log.warn("AI categorization call failed ({}); the run stops and the rest waits for the next run",
                            e.getClass().getName());
                    status = Status.STOPPED_ON_ERROR;
                    break;
                }
                tally.sent += prompt.request().items().size();
                tally.withExamples += (int) prompt.candidateByKey().values().stream()
                        .filter(index -> !similar.get(batch.get(index)).isEmpty()).count();
                write(prompt, batch, answer, selection.categoryIds(), force, tally);
            }
            next += batch.size();
        }
        List<Group> remaining = groups.subList(next, groups.size());
        return new AiRunResult(status, selection.groups().size(), tally.sent, tally.categorized, tally.lowConfidence,
                tally.invalid, tally.personToPerson, tally.redactionBlocked, tally.calls, tally.retries,
                tally.callsWithoutUsage, tally.inputTokens, tally.outputTokens, remaining.size(),
                remaining.stream().mapToInt(group -> group.txnIds().size()).sum(), tally.withExamples, tally.direct,
                embedded.result());
    }

    /** The model's validated answer, or an invalid validation after {@value #ATTEMPTS} invalid answers. */
    private Validation ask(CategorizationPrompt prompt, Set<String> allowed, Tally tally) {
        Set<String> keys = prompt.candidateByKey().keySet();
        Validation validation = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (attempt > 1) {
                tally.retries++;
            }
            Result result = client.categorize(prompt);
            tally.count(result);
            validation = AnswerValidator.validate(result.response(), keys, allowed);
            if (validation.valid()) {
                if (!validation.rejected().isEmpty()) {
                    // Not asked again: the answer lines up with the items, and the same prompt gives the same item.
                    log.warn("AI categorization rejected {} of {} items ({}); they go to review",
                            validation.rejected().size(), keys.size(), validation.itemProblems());
                }
                return validation;
            }
            log.warn("AI categorization answer rejected ({}), attempt {} of {}", validation.problems(), attempt,
                    ATTEMPTS);
        }
        return validation;
    }

    private Selection select(boolean force) {
        List<String> categories = new ArrayList<>();
        Map<String, Long> categoryIds = new HashMap<>();
        jdbc.query("SELECT id, name FROM category ORDER BY name", rs -> {
            categories.add(rs.getString("name"));
            categoryIds.put(rs.getString("name"), rs.getLong("id"));
        });
        Map<List<Object>, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        // Newest first within a merchant and direction, so the first row gives the item's amount (§4.1).
        jdbc.queryForList("""
                SELECT t.id, t.merchant, sign(t.amount)::int AS direction, t.amount, t.counterparty
                FROM txn t
                WHERE t.merchant IS NOT NULL
                  AND NOT t.is_transfer
                  AND (t.category_source IS NULL OR (? AND t.category_source = 'AI'))
                  AND (t.ai_review_reason IS NULL OR ?)
                ORDER BY t.merchant, direction, t.txn_date DESC, t.id DESC""", force, force)
                .forEach(row -> rows.computeIfAbsent(List.of(row.get("merchant"), row.get("direction")),
                        key -> new ArrayList<>()).add(row));
        List<Group> groups = rows.values().stream().map(AiCategorizer::group).toList();
        return new Selection(groups, List.copyOf(categories), Map.copyOf(categoryIds), redactionContext.load());
    }

    private static Group group(List<Map<String, Object>> rows) {
        Map<String, Object> newest = rows.getFirst();
        String counterparty = rows.stream().map(row -> (String) row.get("counterparty"))
                .filter(Objects::nonNull).findFirst().orElse(null);
        return new Group((String) newest.get("merchant"), ((Number) newest.get("direction")).intValue(),
                (BigDecimal) newest.get("amount"), counterparty,
                rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList());
    }

    private static List<Candidate> candidates(List<Group> batch, Map<Group, List<SimilarMerchant>> similar) {
        return batch.stream()
                .map(group -> new Candidate(group.merchant(), group.counterparty(), group.latestAmount(),
                        similar.get(group).stream()
                                .map(example -> new LocalExample(example.merchant(), example.category()))
                                .toList()))
                .toList();
    }

    /** Whether the nearest example is close enough to give its category without a call (direct assignment). */
    private boolean isDirect(List<SimilarMerchant> similar) {
        return !similar.isEmpty() && similar.getFirst().similarity() >= examples.directMinSimilarity();
    }

    /**
     * Direct assignment (off by default; step 9 measures it): the nearest example's category, with the embedding
     * model as the category's model, no confidence and no prompt version, so these rows can be told apart. A
     * person-to-person or redaction-blocked item never has a vector, so it never gets here.
     */
    private void assignDirectly(List<Group> direct, Map<Group, List<SimilarMerchant>> similar,
                                Map<String, Long> categoryIds, boolean force, Tally tally) {
        if (direct.isEmpty()) {
            return;
        }
        List<Object[]> apply = new ArrayList<>();
        for (Group group : direct) {
            long categoryId = categoryIds.get(similar.get(group).getFirst().category());
            group.txnIds().forEach(id -> apply.add(new Object[] {categoryId, embeddingStage.modelId(), null, null, id,
                    group.merchant(), force}));
        }
        tally.direct += direct.size();
        readWrite.executeWithoutResult(status -> {
            lock.acquire();
            tally.categorized += sum(jdbc.batchUpdate(APPLY_SQL, apply));
        });
    }

    private void write(CategorizationPrompt prompt, List<Group> batch, Validation answer, Map<String, Long> categoryIds,
                       boolean force, Tally tally) {
        String model = ai.roles().categorization();
        List<Object[]> apply = new ArrayList<>();
        List<Object[]> review = new ArrayList<>();
        for (Map.Entry<String, Integer> sent : prompt.candidateByKey().entrySet()) {
            Group group = batch.get(sent.getValue());
            Accepted accepted = answer.byKey().get(sent.getKey());
            if (!answer.valid() || accepted == null) {
                tally.invalid++;
                group.txnIds().forEach(id -> review.add(new Object[] {ReviewReason.INVALID_ANSWER.name(), id}));
            } else if (accepted.confidence().compareTo(properties.minConfidence()) < 0) {
                tally.lowConfidence++;
                group.txnIds().forEach(id -> review.add(new Object[] {ReviewReason.LOW_CONFIDENCE.name(), id}));
            } else {
                long categoryId = categoryIds.get(accepted.category());
                group.txnIds().forEach(id -> apply.add(new Object[] {categoryId, model, accepted.confidence(),
                        CategorizationPromptBuilder.PROMPT_VERSION, id, group.merchant(), force}));
            }
        }
        readWrite.executeWithoutResult(status -> {
            lock.acquire();
            tally.categorized += sum(jdbc.batchUpdate(APPLY_SQL, apply));
            // An AI category already there (a forced run) stays: a rejected answer never removes a category.
            jdbc.batchUpdate("""
                    UPDATE txn
                    SET ai_review_reason = ?, ai_review_at = now()
                    WHERE id = ? AND category_id IS NULL AND NOT is_transfer""", review);
        });
    }

    private static int sum(int[] counts) {
        int total = 0;
        for (int count : counts) {
            total += Math.max(count, 0);
        }
        return total;
    }
}
