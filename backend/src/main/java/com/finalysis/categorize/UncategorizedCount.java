package com.finalysis.categorize;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Txns without a category: the run summary's "to review" (step 7e), the same rows as {@code GET
 * /api/transactions?uncategorized=true} counts.
 */
@Component
class UncategorizedCount {

    private final JdbcTemplate jdbc;

    UncategorizedCount(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    long count() {
        return jdbc.queryForObject("SELECT count(*) FROM txn WHERE category_id IS NULL", Long.class);
    }
}
