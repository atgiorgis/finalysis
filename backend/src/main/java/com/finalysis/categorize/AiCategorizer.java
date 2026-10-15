package com.finalysis.categorize;

import com.finalysis.ai.AiProperties;
import com.finalysis.categorize.AnswerValidator.Accepted;
import com.finalysis.categorize.AnswerValidator.Validation;
import com.finalysis.categorize.CategorizationModelClient.Result;
import com.finalysis.categorize.CategorizationPromptBuilder.Candidate;
import com.finalysis.categorize.CategorizationPromptBuilder.CategorizationPrompt;
import com.finalysis.categorize.CategorizationPromptBuilder.Skip;
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
 * <p><b>What it accepts.</b> {@link AnswerValidator} checks every answer. An invalid answer (or one that doesn't
 * parse) is asked again once, with the same batch; if the second is invalid too, the batch's txns go to the review
 * queue ({@code INVALID_ANSWER}). A valid answer below {@code min-confidence} is not applied either
 * ({@code LOW_CONFIDENCE}). Nothing is guessed: those txns stay uncategorized. Transport retries (throttling, 5xx)
 * are the AWS SDK's own, set on the Bedrock client ({@code BedrockClientConfiguration}); a call that still fails
 * stops the run, and its txns are left as they were for the next run.
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

    private final AiProperties ai;
    private final AiCategorizationProperties properties;
    private final ObjectProvider<ChatModel> chatModel;
    private final CategorizationModelClient client;
    private final CategorizationPromptBuilder promptBuilder;
    private final RedactionContextQuery redactionContext;
    private final CategorizationLock lock;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate readWrite;
    private final ReentrantLock running = new ReentrantLock();

    public AiCategorizer(AiProperties ai, AiCategorizationProperties properties, ObjectProvider<ChatModel> chatModel,
                         CategorizationModelClient client, CategorizationPromptBuilder promptBuilder,
                         RedactionContextQuery redactionContext, CategorizationLock lock, JdbcTemplate jdbc,
                         PlatformTransactionManager transactions) {
        this.ai = ai;
        this.properties = properties;
        this.chatModel = chatModel;
        this.client = client;
        this.promptBuilder = promptBuilder;
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
     * @param txnsCategorized    txns given an AI category
     * @param itemsLowConfidence items answered below {@code min-confidence}; their txns are left for review
     * @param itemsInvalid       items in batches answered invalidly twice; their txns are left for review
     * @param personToPerson     items never sent: a person-to-person payment (§5.2)
     * @param redactionBlocked   items never sent: redaction left nothing, or the guard refused (§5.3)
     * @param calls              model calls, retries included
     * @param retries            calls that repeated a batch after an invalid answer
     * @param callsWithoutUsage  calls whose provider reported no token usage; their tokens aren't in the totals
     * @param inputTokens        input tokens of the calls that reported usage
     * @param outputTokens       output tokens of the calls that reported usage
     * @param itemsRemaining     items not reached because of the cap or a failure
     * @param txnsRemaining      their txns
     */
    public record AiRunResult(Status status, int itemsSelected, int itemsSent, int txnsCategorized,
                              int itemsLowConfidence, int itemsInvalid, int personToPerson, int redactionBlocked,
                              int calls, int retries, int callsWithoutUsage, long inputTokens, long outputTokens,
                              int itemsRemaining, int txnsRemaining) {

        static AiRunResult off() {
            return new AiRunResult(Status.OFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * One merchant and direction: the item the model sees, and the txns its answer is written to.
     *
     * @param latestAmount the newest txn's amount, as stored
     * @param counterparty any of its txns' person-to-person counterparty; never sent, it makes the item skipped
     */
    private record Group(String merchant, BigDecimal latestAmount, String counterparty, List<Long> txnIds) {
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
                            + "inputTokens={} outputTokens={} itemsRemaining={} txnsRemaining={}",
                    result.status(), result.itemsSelected(), result.itemsSent(), result.txnsCategorized(),
                    result.itemsLowConfidence(), result.itemsInvalid(), result.personToPerson(),
                    result.redactionBlocked(), result.calls(), result.retries(), result.callsWithoutUsage(),
                    result.inputTokens(), result.outputTokens(), result.itemsRemaining(), result.txnsRemaining());
            return result;
        } finally {
            running.unlock();
        }
    }

    private AiRunResult categorize(boolean force) {
        Selection selection = readOnly.execute(status -> select(force));
        List<Group> groups = selection.groups();
        Set<String> allowed = Set.copyOf(selection.categories());
        Tally tally = new Tally();
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
            CategorizationPrompt prompt = promptBuilder.build(selection.categories(), candidates(batch),
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
                write(prompt, batch, answer, selection.categoryIds(), force, tally);
            }
            next += batch.size();
        }
        List<Group> remaining = groups.subList(next, groups.size());
        return new AiRunResult(status, groups.size(), tally.sent, tally.categorized, tally.lowConfidence,
                tally.invalid, tally.personToPerson, tally.redactionBlocked, tally.calls, tally.retries,
                tally.callsWithoutUsage, tally.inputTokens, tally.outputTokens, remaining.size(),
                remaining.stream().mapToInt(group -> group.txnIds().size()).sum());
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
                SELECT t.id, t.merchant, sign(t.amount) AS direction, t.amount, t.counterparty
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
        return new Group((String) newest.get("merchant"), (BigDecimal) newest.get("amount"), counterparty,
                rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList());
    }

    /** No examples yet: similar merchants arrive with step 8 (CAT-03). */
    private static List<Candidate> candidates(List<Group> batch) {
        return batch.stream()
                .map(group -> new Candidate(group.merchant(), group.counterparty(), group.latestAmount(), List.of()))
                .toList();
    }

    private void write(CategorizationPrompt prompt, List<Group> batch, Validation answer, Map<String, Long> categoryIds,
                       boolean force, Tally tally) {
        String model = ai.roles().categorization();
        List<Object[]> apply = new ArrayList<>();
        List<Object[]> review = new ArrayList<>();
        for (Map.Entry<String, Integer> sent : prompt.candidateByKey().entrySet()) {
            Group group = batch.get(sent.getValue());
            Accepted accepted = answer.byKey().get(sent.getKey());
            if (!answer.valid()) {
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
            tally.categorized += sum(jdbc.batchUpdate("""
                    UPDATE txn
                    SET category_id = ?, category_source = 'AI', category_rule_id = NULL, categorized_at = now(),
                        category_model = ?, category_confidence = ?, category_prompt_version = ?,
                        ai_review_reason = NULL, ai_review_at = NULL
                    WHERE id = ?
                      AND merchant = ?
                      AND NOT is_transfer
                      AND (category_source IS NULL OR (? AND category_source = 'AI'))""", apply));
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
