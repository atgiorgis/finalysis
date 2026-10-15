package com.finalysis.categorize;

import com.finalysis.categorize.MerchantCleaningService.CleaningResult;
import com.finalysis.categorize.RuleEngine.Rule;
import com.finalysis.categorize.RuleEngine.Subject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stages 2 and 3 of the categorization pipeline (docs/plans/phase-4.md §1.1, §1.3): rules, then merchant
 * memory. No AI: stage 3 only copies categories an earlier AI answer gave the same merchant.
 *
 * <p>Rules are recomputed on every pass, for every txn in scope whose category the user didn't set: a match
 * sets RULE (replacing AI or an older rule), and a RULE txn that no longer matches is cleared. An AI txn
 * that no rule matches keeps its category. Only rows whose values change are written, so a second pass
 * writes nothing. Every write is conditional on the source, so a user's category is never overwritten.
 *
 * <p>Each pass takes the {@link CategorizationLock}. {@link #categorizeStatement} runs in a transaction of
 * its own, for the after-import listener; the others join the caller's, so a rule change and the pass it
 * triggers commit together. Nothing here logs text: counts and rule ids only (SEC-10).
 */
@Service
public class CategorizationService {

    private static final Logger log = LoggerFactory.getLogger(CategorizationService.class);
    private static final int BATCH = 1000;

    /**
     * For every write that sets a category other than the AI's (V16): the AI provenance goes with the AI category,
     * and a txn that has a category is no longer waiting for review.
     */
    static final String CLEAR_AI_COLUMNS = "category_model = NULL, category_confidence = NULL, "
            + "category_prompt_version = NULL, ai_review_reason = NULL, ai_review_at = NULL";

    private final CategorizationLock lock;
    private final MerchantCleaningService cleaning;
    private final JdbcTemplate jdbc;

    public CategorizationService(CategorizationLock lock, MerchantCleaningService cleaning, JdbcTemplate jdbc) {
        this.lock = lock;
        this.cleaning = cleaning;
        this.jdbc = jdbc;
    }

    /**
     * Counts from one rules pass; numbers only, so safe to log.
     *
     * @param ruleCategorized txns given a rule category, or a different one
     * @param ruleCleared     RULE txns that no rule matches any more, now uncategorized
     * @param memoryApplied   uncategorized txns given their merchant's earlier AI category
     */
    public record RulesResult(int examined, int ruleCategorized, int ruleCleared, int memoryApplied) {}

    /**
     * Body of {@code POST /api/categorization/run}. AI stages are added in Phase 4 steps 7 and 8.
     *
     * @param merchantsCleaned txns whose merchant or counterparty changed
     */
    public record RunResult(int merchantsCleaned, int ruleCategorized, int ruleCleared, int memoryApplied) {}

    /** Stages 2–3 for one statement's txns: run after its import commits and its merchants are cleaned. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RulesResult categorizeStatement(long statementId) {
        lock.acquire();
        return pass("AND t.statement_id = ?", statementId);
    }

    /** Stages 2–3 for every txn: at startup, and after a change through the rules API. */
    @Transactional
    public RulesResult categorizeAll() {
        lock.acquire();
        return pass("");
    }

    /** Stages 2–3 for one merchant's txns: after a correction upserts that merchant's rule. */
    @Transactional
    public RulesResult categorizeMerchant(String merchant) {
        lock.acquire();
        return pass("AND t.merchant = ?", merchant);
    }

    /** Stages 2–3 for one txn: after the user clears its category. */
    @Transactional
    public RulesResult categorizeTxn(long txnId) {
        lock.acquire();
        return pass("AND t.id = ?", txnId);
    }

    /**
     * Every stage there is, now (§1.2): merchant cleaning (rows without a merchant, or every row with
     * {@code reclean}), then rules and memory for every txn.
     */
    @Transactional
    public RunResult run(boolean reclean) {
        lock.acquire();
        CleaningResult cleaned = cleaning.cleanOnDemand(reclean);
        RulesResult rules = pass("");
        return new RunResult(cleaned.updated(), rules.ruleCategorized(), rules.ruleCleared(), rules.memoryApplied());
    }

    /** The active rules, as the engine sees them. */
    @Transactional(readOnly = true)
    public List<Rule> activeRules() {
        return jdbc.query("""
                SELECT id, field, match_type, pattern, direction, priority, source, category_id
                FROM categorization_rule
                WHERE active""",
                (rs, row) -> new Rule(rs.getLong("id"), RuleField.valueOf(rs.getString("field")),
                        RuleMatchType.valueOf(rs.getString("match_type")), rs.getString("pattern"),
                        RuleDirection.valueOf(rs.getString("direction")), rs.getInt("priority"),
                        RuleSource.valueOf(rs.getString("source")), rs.getLong("category_id")));
    }

    private record Current(long id, Long categoryId, CategorySource source, Long ruleId) {}

    private RulesResult pass(String scope, Object... args) {
        RuleEngine engine = new RuleEngine(activeRules());
        List<Object[]> categorize = new ArrayList<>();
        List<Object[]> clear = new ArrayList<>();
        int[] examined = {0};
        jdbc.query("""
                SELECT t.id, t.merchant, t.raw_description, t.amount, t.category_id, t.category_source, t.category_rule_id
                FROM txn t
                WHERE t.category_source IS DISTINCT FROM 'USER'
                """ + scope + " ORDER BY t.id", rs -> {
            examined[0]++;
            String source = rs.getString("category_source");
            Current current = new Current(rs.getLong("id"), rs.getObject("category_id", Long.class),
                    source == null ? null : CategorySource.valueOf(source), rs.getObject("category_rule_id", Long.class));
            Optional<Rule> winner = engine.winner(
                    Subject.of(rs.getString("merchant"), rs.getString("raw_description"), rs.getBigDecimal("amount")));
            if (winner.isPresent()) {
                Rule rule = winner.get();
                boolean unchanged = current.source() == CategorySource.RULE
                        && Long.valueOf(rule.id()).equals(current.ruleId())
                        && Long.valueOf(rule.categoryId()).equals(current.categoryId());
                if (!unchanged) {
                    categorize.add(new Object[] {rule.categoryId(), rule.id(), current.id()});
                }
            } else if (current.source() == CategorySource.RULE) {
                clear.add(new Object[] {current.id()});
            }
        }, args);

        int categorized = update("""
                UPDATE txn
                SET category_id = ?, category_source = 'RULE', category_rule_id = ?, categorized_at = now(), %s
                WHERE id = ? AND category_source IS DISTINCT FROM 'USER'""".formatted(CLEAR_AI_COLUMNS), categorize);
        int cleared = update("""
                UPDATE txn
                SET category_id = NULL, category_source = NULL, category_rule_id = NULL, categorized_at = NULL
                WHERE id = ? AND category_source = 'RULE'""", clear);
        int memory = applyMemory(scope, args);
        warnAbout(engine.invalidRules(), "don't compile and were skipped");
        warnAbout(engine.timedOutRules(), "ran past the regex deadline and were skipped on some txns");
        return new RulesResult(examined[0], categorized, cleared, memory);
    }

    /**
     * Stage 3: an uncategorized txn takes the latest AI category of its exact merchant in the same
     * direction, so a merchant goes to the model once. Linked txns are left out, as the AI leaves them out
     * (§1.1). Copies from AI txns only, so a user's decision always comes through a rule. The model, confidence
     * and prompt version are copied with the category, so the txn says where its category came from; a txn waiting
     * for review gets its merchant's answer as well.
     */
    private int applyMemory(String scope, Object... args) {
        return jdbc.update("""
                UPDATE txn t
                SET category_id = m.category_id, category_source = 'AI', categorized_at = now(),
                    category_model = m.category_model, category_confidence = m.category_confidence,
                    category_prompt_version = m.category_prompt_version, ai_review_reason = NULL, ai_review_at = NULL
                FROM (
                    SELECT DISTINCT ON (merchant, sign(amount)) merchant, sign(amount) AS direction, category_id,
                           category_model, category_confidence, category_prompt_version
                    FROM txn
                    WHERE category_source = 'AI' AND merchant IS NOT NULL
                    ORDER BY merchant, sign(amount), categorized_at DESC NULLS LAST, id DESC
                ) m
                WHERE t.category_source IS NULL
                  AND NOT t.is_transfer
                  AND t.merchant = m.merchant
                  AND sign(t.amount) = m.direction
                """ + scope, args);
    }

    private int update(String sql, List<Object[]> rows) {
        int updated = 0;
        for (int from = 0; from < rows.size(); from += BATCH) {
            for (int count : jdbc.batchUpdate(sql, rows.subList(from, Math.min(from + BATCH, rows.size())))) {
                updated += count;
            }
        }
        return updated;
    }

    private static void warnAbout(Set<Long> ruleIds, String problem) {
        if (!ruleIds.isEmpty()) {
            log.warn("Categorization rules {} {}", ruleIds.stream().sorted().toList(), problem);
        }
    }
}
