package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional // each test's inserts roll back
class CategorizationRuleRepositoryTest {

    private final CategorizationRuleRepository rules;
    private final CategoryRepository categories;
    private final EntityManager entityManager;

    CategorizationRuleRepositoryTest(CategorizationRuleRepository rules, CategoryRepository categories,
                                     EntityManager entityManager) {
        this.rules = rules;
        this.categories = categories;
        this.entityManager = entityManager;
    }

    @Test
    void savesAndLoadsRuleWithColumnDefaults() {
        Category groceries = categories.findByName("Groceries").orElseThrow();
        Long id = rules.saveAndFlush(
                new CategorizationRule("GREENLEAF MARKET", RuleMatchType.CONTAINS, groceries, RuleSource.SEED)).getId();
        entityManager.clear(); // load from the database, not the persistence context

        CategorizationRule loaded = rules.findById(id).orElseThrow();

        assertThat(loaded.getPattern()).isEqualTo("GREENLEAF MARKET");
        assertThat(loaded.getMatchType()).isEqualTo(RuleMatchType.CONTAINS);
        assertThat(loaded.getCategory().getName()).isEqualTo("Groceries");
        assertThat(loaded.getSource()).isEqualTo(RuleSource.SEED);
        assertThat(loaded.getPriority()).isZero();
        assertThat(loaded.isActive()).isTrue();
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void savesUserCorrectionRegexRule() {
        Category dining = categories.findByName("Dining").orElseThrow();
        CategorizationRule rule = new CategorizationRule(
                "^SQ \\*.*COFFEE", RuleMatchType.REGEX, dining, RuleSource.USER_CORRECTION);
        rule.setPriority(100);
        Long id = rules.saveAndFlush(rule).getId();
        entityManager.clear();

        CategorizationRule loaded = rules.findById(id).orElseThrow();

        assertThat(loaded.getPattern()).isEqualTo("^SQ \\*.*COFFEE");
        assertThat(loaded.getMatchType()).isEqualTo(RuleMatchType.REGEX);
        assertThat(loaded.getSource()).isEqualTo(RuleSource.USER_CORRECTION);
        assertThat(loaded.getPriority()).isEqualTo(100);
    }
}
