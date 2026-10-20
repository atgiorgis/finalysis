package com.finalysis.analytics;

import com.finalysis.api.NotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Accounts and categories as analytics names them, read in plain SQL so analytics depends on neither feature's
 * classes. An unknown id is a 404, never an empty total: "0.00 spent on account 99" would read as an answer
 * (docs/plans/phase-5.md §5.1). Public for the alerts (step 5); it runs in the caller's transaction.
 */
@Component
public class ReferenceQuery {

    private final JdbcClient jdbc;

    ReferenceQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void requireAccount(long id) {
        if (!exists("SELECT EXISTS (SELECT 1 FROM account WHERE id = :id)", id)) {
            throw new NotFoundException("Account", id);
        }
    }

    void requireCategory(long id) {
        if (!exists("SELECT EXISTS (SELECT 1 FROM category WHERE id = :id)", id)) {
            throw new NotFoundException("Category", id);
        }
    }

    /** Every account's name, by id. */
    Map<Long, String> accounts() {
        Map<Long, String> accounts = new LinkedHashMap<>();
        jdbc.sql("SELECT id, name FROM account ORDER BY id")
                .query(rs -> {
                    accounts.put(rs.getLong("id"), rs.getString("name"));
                });
        return accounts;
    }

    /** Every category, by id. */
    public Map<Long, CategoryRef> categories() {
        Map<Long, CategoryRef> categories = new LinkedHashMap<>();
        jdbc.sql("SELECT id, name, kind FROM category ORDER BY id")
                .query(rs -> {
                    categories.put(rs.getLong("id"), new CategoryRef(rs.getLong("id"), rs.getString("name"),
                            CategoryKind.valueOf(rs.getString("kind"))));
                });
        return categories;
    }

    private boolean exists(String sql, long id) {
        return Boolean.TRUE.equals(jdbc.sql(sql).param("id", id).query(Boolean.class).single());
    }
}
