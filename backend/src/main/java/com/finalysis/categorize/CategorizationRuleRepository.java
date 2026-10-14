package com.finalysis.categorize;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CategorizationRuleRepository extends JpaRepository<CategorizationRule, Long> {

    /** Every rule with its category, in one query. */
    @Query("SELECT r FROM CategorizationRule r JOIN FETCH r.category")
    List<CategorizationRule> findAllWithCategory();

    /** The active user rule that {@code uq_categorization_rule_user_pattern} would collide with. */
    @Query("""
            SELECT r FROM CategorizationRule r
            WHERE r.active AND r.source <> com.finalysis.categorize.RuleSource.SEED
              AND r.field = :field AND r.matchType = :matchType AND r.pattern = :pattern AND r.direction = :direction""")
    Optional<CategorizationRule> findActiveUserRule(RuleField field, RuleMatchType matchType, String pattern,
                                                    RuleDirection direction);
}
