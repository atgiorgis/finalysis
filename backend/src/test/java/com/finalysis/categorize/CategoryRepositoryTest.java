package com.finalysis.categorize;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional // each test's inserts roll back
class CategoryRepositoryTest {

    /** Every row of V2__seed_categories.sql. */
    private static final Map<String, CategoryKind> SEEDED = Map.ofEntries(
            entry("Dining", CategoryKind.EXPENSE),
            entry("Groceries", CategoryKind.EXPENSE),
            entry("Health", CategoryKind.EXPENSE),
            entry("Housing", CategoryKind.EXPENSE),
            entry("Shopping", CategoryKind.EXPENSE),
            entry("Subscriptions", CategoryKind.EXPENSE),
            entry("Transportation", CategoryKind.EXPENSE),
            entry("Utilities", CategoryKind.EXPENSE),
            entry("Income", CategoryKind.INCOME),
            entry("Interest", CategoryKind.INCOME),
            entry("Transfer", CategoryKind.TRANSFER),
            entry("Bank Fees", CategoryKind.EXPENSE),
            entry("Cash & ATM", CategoryKind.EXPENSE),
            entry("Mortgage", CategoryKind.EXPENSE),
            entry("HOA & Condo Fees", CategoryKind.EXPENSE),
            entry("Tolls", CategoryKind.EXPENSE),
            entry("Person-to-Person Out", CategoryKind.EXPENSE),
            entry("Person-to-Person In", CategoryKind.INCOME),
            entry("Refunds & Rebates", CategoryKind.INCOME));

    private final CategoryRepository categories;
    private final EntityManager entityManager;

    CategoryRepositoryTest(CategoryRepository categories, EntityManager entityManager) {
        this.categories = categories;
        this.entityManager = entityManager;
    }

    @Test
    void everySeededCategoryLoadsWithItsKind() {
        Map<String, CategoryKind> loaded = categories.findAll().stream()
                .collect(Collectors.toMap(Category::getName, Category::getKind));

        assertThat(loaded).containsExactlyInAnyOrderEntriesOf(SEEDED);
    }

    @Test
    void seededCategoriesAreTopLevel() {
        assertThat(categories.findAll()).allSatisfy(category -> assertThat(category.getParent()).isNull());
    }

    @Test
    void savesAndLoadsCategoryWithParent() {
        Category dining = categories.findByName("Dining").orElseThrow();
        Category coffee = new Category("Coffee Shops", CategoryKind.EXPENSE);
        coffee.setParent(dining);
        Long id = categories.saveAndFlush(coffee).getId();
        entityManager.clear(); // load from the database, not the persistence context

        Category loaded = categories.findById(id).orElseThrow();

        assertThat(loaded.getName()).isEqualTo("Coffee Shops");
        assertThat(loaded.getKind()).isEqualTo(CategoryKind.EXPENSE);
        assertThat(loaded.getParent().getId()).isEqualTo(dining.getId());
        assertThat(loaded.getParent().getName()).isEqualTo("Dining");
    }
}
