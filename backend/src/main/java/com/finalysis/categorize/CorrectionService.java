package com.finalysis.categorize;

import com.finalysis.api.NotFoundException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User corrections (CAT-04; docs/plans/phase-4.md §9.2): the user sets or clears a txn's category. User
 * actions over the API only; chatbot tools never call these (SEC-03).
 *
 * <p>A correction sets {@code USER} on the txn, which no stage ever overwrites (§1.3). With
 * {@code applyToMerchant} and a merchant, it also upserts that merchant's correction rule: field
 * {@code MERCHANT}, match type {@code EXACT}, the txn's direction (money out, money in, or any for a zero
 * amount), source {@code USER_CORRECTION}, default priority. Correcting the same merchant again changes that
 * one rule's category ({@code uq_categorization_rule_user_pattern}). Then the rules run for that merchant, so
 * its other txns follow unless the user set them or a higher-ranked user rule wins.
 *
 * <p>Part of the categorization code, so the txn's category columns keep a single writer (step 2 notes):
 * SQL only, under the categorization lock, in the caller's transaction.
 */
@Service
public class CorrectionService {

    private final CategorizationRuleRepository rules;
    private final CategoryRepository categories;
    private final CategorizationService categorization;
    private final CategorizationLock lock;
    private final JdbcTemplate jdbc;

    public CorrectionService(CategorizationRuleRepository rules, CategoryRepository categories,
                             CategorizationService categorization, CategorizationLock lock, JdbcTemplate jdbc) {
        this.rules = rules;
        this.categories = categories;
        this.categorization = categorization;
        this.lock = lock;
        this.jdbc = jdbc;
    }

    /** What a correction did to the merchant's rule. */
    public enum RuleAction {
        /** A new correction rule. */
        CREATED,
        /** The merchant's existing user rule now points to the new category. */
        UPDATED,
        /** The merchant's existing user rule already pointed to it. */
        UNCHANGED,
        /** Undo only: the merchant's correction rule was deleted. */
        REMOVED,
        /** No rule: {@code applyToMerchant} was false, the txn has no merchant, or (undo) no correction rule. */
        NONE
    }

    /**
     * What a correction changed. The other txns are those the rule matches: the same merchant and direction,
     * the corrected txn left out. All zero when no rule was touched.
     *
     * @param otherTxnsUpdated       other txns whose category changed
     * @param otherTxnsKeptUser      other txns the user had categorized, left as they are
     * @param otherTxnsKeptOtherRule other txns where a higher-ranked user rule still wins
     */
    public record Correction(long txnId, Long ruleId, RuleAction ruleAction, int otherTxnsUpdated,
                             int otherTxnsKeptUser, int otherTxnsKeptOtherRule) {}

    /**
     * What a full undo changed.
     *
     * @param ruleId           the deleted correction rule, null when none was deleted ({@code ruleAction} {@code NONE})
     * @param otherTxnsUpdated the merchant's other txns whose category changed
     */
    public record Undo(long txnId, Long ruleId, RuleAction ruleAction, int otherTxnsUpdated) {}

    private record Row(long id, Long categoryId, CategorySource source, Long ruleId) {}

    @Transactional
    public Correction correct(long txnId, long categoryId, boolean applyToMerchant) {
        lock.acquire();
        Map<String, Object> txn = txn(txnId);
        Category category = categories.findById(categoryId).orElseThrow(() -> new CategoryInvalidException(categoryId));
        jdbc.update("""
                UPDATE txn
                SET category_id = ?, category_source = 'USER', category_rule_id = NULL, categorized_at = now()
                WHERE id = ?""", categoryId, txnId);

        String merchant = (String) txn.get("merchant");
        if (!applyToMerchant || merchant == null) {
            return new Correction(txnId, null, RuleAction.NONE, 0, 0, 0);
        }
        RuleDirection direction = direction((BigDecimal) txn.get("amount"));
        Map<Long, Row> before = others(merchant, direction, txnId);

        CategorizationRule rule = rules.findActiveUserRule(RuleField.MERCHANT, RuleMatchType.EXACT, merchant, direction)
                .orElse(null);
        RuleAction action;
        if (rule == null) {
            // The merchant is stored cleaned (upper case, single spaces), so it is already a normalized pattern.
            rule = new CategorizationRule(merchant, RuleMatchType.EXACT, category, RuleSource.USER_CORRECTION);
            rule.setDirection(direction);
            action = RuleAction.CREATED;
        } else if (rule.getCategory().getId().equals(categoryId)) {
            action = RuleAction.UNCHANGED;
        } else {
            rule.setCategory(category);
            action = RuleAction.UPDATED;
        }
        long ruleId = rules.saveAndFlush(rule).getId();
        categorization.categorizeMerchant(merchant);

        int updated = 0;
        int keptUser = 0;
        int keptOtherRule = 0;
        for (Row after : others(merchant, direction, txnId).values()) {
            Row was = before.get(after.id());
            if (after.source() == CategorySource.USER) {
                keptUser++;
                continue;
            }
            if (!Objects.equals(was.categoryId(), after.categoryId())) {
                updated++;
            }
            if (!Long.valueOf(ruleId).equals(after.ruleId())) {
                keptOtherRule++;
            }
        }
        return new Correction(txnId, ruleId, action, updated, keptUser, keptOtherRule);
    }

    /**
     * Clears a {@code USER} category: the txn is eligible again and the rules run for it now, so a correction
     * rule for its merchant, if one remains, categorizes it as {@code RULE}. A txn the user didn't categorize is
     * left as it is.
     */
    @Transactional
    public void clear(long txnId) {
        lock.acquire();
        txn(txnId);
        int cleared = jdbc.update("""
                UPDATE txn
                SET category_id = NULL, category_source = NULL, category_rule_id = NULL, categorized_at = NULL
                WHERE id = ? AND category_source = 'USER'""", txnId);
        if (cleared > 0) {
            categorization.categorizeTxn(txnId);
        }
    }

    /**
     * A full undo in one call: clears a {@code USER} category as {@link #clear} does, deletes the correction rule
     * for the txn's merchant and direction, then runs the rules for the merchant's txns, which go back to the
     * seeds, another rule, or uncategorized. Only a {@code USER_CORRECTION} rule is deleted: a rule the user made
     * through the rules API ({@code USER}) or a seed is kept, and {@code ruleAction} is {@code NONE}. The rule is
     * deleted even if the txn's category was already cleared, so the two-call undo can be finished here.
     */
    @Transactional
    public Undo undo(long txnId) {
        lock.acquire();
        Map<String, Object> txn = txn(txnId);
        jdbc.update("""
                UPDATE txn
                SET category_id = NULL, category_source = NULL, category_rule_id = NULL, categorized_at = NULL
                WHERE id = ? AND category_source = 'USER'""", txnId);

        String merchant = (String) txn.get("merchant");
        if (merchant == null) {
            categorization.categorizeTxn(txnId);
            return new Undo(txnId, null, RuleAction.NONE, 0);
        }
        CategorizationRule rule = rules.findActiveUserRule(RuleField.MERCHANT, RuleMatchType.EXACT, merchant,
                        direction((BigDecimal) txn.get("amount")))
                .filter(found -> found.getSource() == RuleSource.USER_CORRECTION)
                .orElse(null);
        Map<Long, Row> before = others(merchant, RuleDirection.ANY, txnId);
        if (rule != null) {
            rules.delete(rule);
            rules.flush(); // category_rule_id is set to null before the pass re-evaluates those txns
        }
        categorization.categorizeMerchant(merchant);

        int updated = 0;
        for (Row after : others(merchant, RuleDirection.ANY, txnId).values()) {
            if (!Objects.equals(before.get(after.id()).categoryId(), after.categoryId())) {
                updated++;
            }
        }
        return rule == null
                ? new Undo(txnId, null, RuleAction.NONE, updated)
                : new Undo(txnId, rule.getId(), RuleAction.REMOVED, updated);
    }

    /** A rule's direction for this amount: money out, money in, or any for zero, which neither matches. */
    private static RuleDirection direction(BigDecimal amount) {
        return switch (amount.signum()) {
            case -1 -> RuleDirection.OUT;
            case 1 -> RuleDirection.IN;
            default -> RuleDirection.ANY;
        };
    }

    /** The txn's merchant and amount, its row locked against a concurrent statement delete. */
    private Map<String, Object> txn(long txnId) {
        return jdbc.queryForList("SELECT merchant, amount FROM txn WHERE id = ? FOR UPDATE", txnId).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Transaction", txnId));
    }

    /** The merchant's txns in this direction (all of them for {@code ANY}), other than the given one. */
    private Map<Long, Row> others(String merchant, RuleDirection direction, long txnId) {
        String sign = switch (direction) {
            case OUT -> " AND amount < 0";
            case IN -> " AND amount > 0";
            case ANY -> "";
        };
        Map<Long, Row> rows = new HashMap<>();
        jdbc.query("""
                SELECT id, category_id, category_source, category_rule_id
                FROM txn
                WHERE merchant = ? AND id <> ?""" + sign, rs -> {
            String source = rs.getString("category_source");
            Row row = new Row(rs.getLong("id"), rs.getObject("category_id", Long.class),
                    source == null ? null : CategorySource.valueOf(source), rs.getObject("category_rule_id", Long.class));
            rows.put(row.id(), row);
        }, merchant, txnId);
        return rows;
    }
}
