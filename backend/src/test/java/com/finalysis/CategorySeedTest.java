package com.finalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.support.IntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Checks the categories seeded by V2__seed_categories.sql (DAT-02). */
@IntegrationTest
class CategorySeedTest {

    private static final Path ANSWER_KEY = Path.of("..", "sample-data", "answer-key.csv");
    private static final Path DEV_SET = Path.of("..", "sample-data", "categorization", "dev.csv");
    private static final Path HOLDOUT_SET = Path.of("..", "sample-data", "categorization", "holdout.csv");

    /** General-purpose categories seeded beyond the answer key (Internal Transfer and Credit Card Payment merged into Transfer). */
    private static final Set<String> GENERAL_PURPOSE = Set.of(
            "Bank Fees", "Cash & ATM", "Mortgage", "HOA & Condo Fees", "Tolls",
            "Person-to-Person Out", "Person-to-Person In", "Refunds & Rebates");

    private final JdbcTemplate jdbc;

    CategorySeedTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void everyAnswerKeyCategoryIsSeeded() throws IOException {
        assertThat(seededNames()).containsAll(answerKeyCategories());
    }

    @Test
    void onlyGeneralPurposeCategoriesAreSeededBeyondAnswerKey() throws IOException {
        Set<String> extra = new HashSet<>(seededNames());
        extra.removeAll(answerKeyCategories());

        assertThat(extra).containsExactlyInAnyOrderElementsOf(GENERAL_PURPOSE);
    }

    /** The CAT-05 dev and holdout sets (step 1b) use only seeded categories, and each exercises all of them. */
    @Test
    void theCategorizationSetsUseEverySeededCategory() throws IOException {
        for (Path set : List.of(DEV_SET, HOLDOUT_SET)) {
            Set<String> categories = new TreeSet<>();
            for (String line : Files.readAllLines(set).stream().filter(l -> !l.startsWith("#")).skip(1).toList()) {
                categories.add(line.split(",", -1)[2]);
            }
            assertThat(categories).as(set.toString()).containsExactlyInAnyOrderElementsOf(seededNames());
        }
    }

    @Test
    void everyCategoryHasValidKind() {
        List<String> kinds = jdbc.queryForList("SELECT kind FROM category", String.class);

        assertThat(kinds).isNotEmpty().allMatch(Set.of("EXPENSE", "INCOME", "TRANSFER")::contains);
    }

    @Test
    void categoryNamesAreUnique() {
        List<String> names = jdbc.queryForList("SELECT name FROM category", String.class);

        assertThat(names).doesNotHaveDuplicates();
    }

    private List<String> seededNames() {
        return jdbc.queryForList("SELECT name FROM category", String.class);
    }

    /** Distinct expected_category values; the generator guarantees no field contains a comma or quote. */
    private static Set<String> answerKeyCategories() throws IOException {
        List<String> lines = Files.readAllLines(ANSWER_KEY);
        int column = List.of(lines.get(0).split(",", -1)).indexOf("expected_category");
        Set<String> categories = new TreeSet<>();
        for (String line : lines.subList(1, lines.size())) {
            categories.add(line.split(",", -1)[column]);
        }
        assertThat(categories).as("answer-key categories").isNotEmpty();
        return categories;
    }
}
