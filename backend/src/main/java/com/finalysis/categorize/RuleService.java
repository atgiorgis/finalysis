package com.finalysis.categorize;

import com.finalysis.api.NotFoundException;
import com.finalysis.categorize.RuleEngine.Rule;
import com.finalysis.categorize.RuleEngine.Subject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rules API (CAT-01, CAT-04; docs/plans/phase-4.md §3, §9.2): list, create, change and delete rules.
 * User actions over the API only; chatbot tools never call these (SEC-03).
 *
 * <p>Every change takes the categorization lock and re-runs the rules over every txn in the same
 * transaction, so it applies at once and commits together with its effect. Seeded rules can only be
 * deactivated or reactivated.
 *
 * <p>Patterns are normalized before they are stored: a text pattern ({@code EXACT}, {@code PREFIX},
 * {@code CONTAINS}) as descriptions are for matching (upper case, accents removed, single spaces), so
 * {@code Café} is stored as {@code CAFE}. A regex is only stripped of surrounding spaces, since upper-casing
 * it would change its meaning, and is matched case-insensitively.
 */
@Service
public class RuleService {

    private final CategorizationRuleRepository rules;
    private final CategoryRepository categories;
    private final CategorizationService categorization;
    private final CategorizationLock lock;
    private final JdbcTemplate jdbc;

    public RuleService(CategorizationRuleRepository rules, CategoryRepository categories,
                       CategorizationService categorization, CategorizationLock lock, JdbcTemplate jdbc) {
        this.rules = rules;
        this.categories = categories;
        this.categorization = categorization;
        this.lock = lock;
        this.jdbc = jdbc;
    }

    /** Every rule that passes the filters (null = no filter), in winning order. */
    @Transactional(readOnly = true)
    public List<RuleResponse> list(RuleSource source, Boolean active, Long categoryId) {
        Comparator<CategorizationRule> winningOrder = Comparator.comparing(CategorizationRule::toEngineRule, RuleEngine.ORDER);
        return rules.findAllWithCategory().stream()
                .filter(rule -> source == null || rule.getSource() == source)
                .filter(rule -> active == null || rule.isActive() == active)
                .filter(rule -> categoryId == null || rule.getCategory().getId().equals(categoryId))
                .sorted(winningOrder)
                .map(RuleResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public RuleResponse get(long id) {
        return RuleResponse.from(rule(id));
    }

    /** Creates a {@code USER} rule and applies it. */
    @Transactional
    public RuleChangeResponse create(CreateRuleRequest request) {
        lock.acquire();
        CategorizationRule rule = new CategorizationRule(normalize(request.matchType(), request.pattern()),
                request.matchType(), category(request.categoryId()), RuleSource.USER);
        if (request.field() != null) {
            rule.setField(request.field());
        }
        if (request.direction() != null) {
            rule.setDirection(request.direction());
        }
        if (request.priority() != null) {
            rule.setPriority(request.priority());
        }
        checkUnique(null, rule.getField(), rule.getMatchType(), rule.getPattern(), rule.getDirection());
        return applied(rules.saveAndFlush(rule));
    }

    /** Changes a user rule, or activates or deactivates a seeded one, and applies the change. */
    @Transactional
    public RuleChangeResponse update(long id, UpdateRuleRequest request) {
        lock.acquire();
        CategorizationRule rule = rule(id);
        if (rule.getSource() == RuleSource.SEED && request.changesMoreThanActive()) {
            throw new RuleSeededException("A seeded rule can only be activated or deactivated");
        }
        RuleField field = request.field() != null ? request.field() : rule.getField();
        RuleMatchType matchType = request.matchType() != null ? request.matchType() : rule.getMatchType();
        String pattern = request.matchType() != null || request.pattern() != null
                ? normalize(matchType, request.pattern() != null ? request.pattern() : rule.getPattern())
                : rule.getPattern();
        RuleDirection direction = request.direction() != null ? request.direction() : rule.getDirection();
        boolean active = request.active() != null ? request.active() : rule.isActive();
        Category category = request.categoryId() != null ? category(request.categoryId()) : rule.getCategory();
        // Checked before the entity changes: the query would flush a change into the unique index first.
        if (active && rule.getSource().isUser()) {
            checkUnique(id, field, matchType, pattern, direction);
        }

        rule.setField(field);
        rule.setMatchType(matchType);
        rule.setPattern(pattern);
        rule.setDirection(direction);
        rule.setActive(active);
        rule.setCategory(category);
        if (request.priority() != null) {
            rule.setPriority(request.priority());
        }
        return applied(rules.saveAndFlush(rule));
    }

    /** Deletes a user rule; the txns it categorized are re-evaluated (another rule, or uncategorized). */
    @Transactional
    public void delete(long id) {
        lock.acquire();
        CategorizationRule rule = rule(id);
        if (rule.getSource() == RuleSource.SEED) {
            throw new RuleSeededException("A seeded rule can't be deleted; deactivate it instead");
        }
        rules.delete(rule);
        rules.flush(); // txn.category_rule_id is set to null before the pass re-evaluates those txns
        categorization.categorizeAll();
    }

    /**
     * The stored form of a pattern for its match type.
     *
     * @throws RuleInvalidException if it's empty or too long once normalized, or a regex that doesn't compile
     */
    static String normalize(RuleMatchType matchType, String pattern) {
        String normalized = matchType == RuleMatchType.REGEX ? pattern.strip() : MerchantCleaner.normalize(pattern);
        if (normalized.isEmpty() || normalized.length() > RuleEngine.MAX_PATTERN) {
            throw new RuleInvalidException("pattern", "must be 1 to " + RuleEngine.MAX_PATTERN + " characters");
        }
        if (matchType == RuleMatchType.REGEX) {
            try {
                RuleEngine.compile(normalized);
            } catch (PatternSyntaxException e) {
                throw new RuleInvalidException("pattern", "is not a valid regular expression");
            }
        }
        return normalized;
    }

    private RuleChangeResponse applied(CategorizationRule rule) {
        categorization.categorizeAll();
        return impact(rule);
    }

    /** §3.2: which txns the rule matches and where it wins, counted as if it were active. */
    private RuleChangeResponse impact(CategorizationRule saved) {
        Rule rule = saved.toEngineRule();
        List<Rule> ranked = new ArrayList<>(categorization.activeRules().stream()
                .filter(other -> other.id() != rule.id()).toList());
        ranked.add(rule);
        RuleEngine engine = new RuleEngine(ranked);
        int[] counts = {0, 0}; // matches, wins
        Set<Long> winners = new HashSet<>();
        jdbc.query("SELECT merchant, raw_description, amount FROM txn", rs -> {
            Subject subject = Subject.of(rs.getString("merchant"), rs.getString("raw_description"), rs.getBigDecimal("amount"));
            if (engine.matches(rule.id(), subject)) {
                counts[0]++;
                long winner = engine.winner(subject).orElseThrow().id();
                if (winner == rule.id()) {
                    counts[1]++;
                } else {
                    winners.add(winner);
                }
            }
        });
        Long shadowedBy = counts[0] > 0 && counts[1] == 0 && winners.size() == 1 ? winners.iterator().next() : null;
        return new RuleChangeResponse(RuleResponse.from(saved), counts[0], counts[1], shadowedBy);
    }

    /** An active user rule with these values must not exist, other than the rule {@code self} being changed. */
    private void checkUnique(Long self, RuleField field, RuleMatchType matchType, String pattern,
                             RuleDirection direction) {
        rules.findActiveUserRule(field, matchType, pattern, direction)
                .filter(existing -> !existing.getId().equals(self))
                .ifPresent(existing -> {
                    throw new RuleExistsException(existing.getId());
                });
    }

    private CategorizationRule rule(long id) {
        return rules.findById(id).orElseThrow(() -> new NotFoundException("Rule", id));
    }

    private Category category(long id) {
        return categories.findById(id).orElseThrow(() -> new CategoryInvalidException(id));
    }
}
