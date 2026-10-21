package com.finalysis.chat.tool;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The category names for the context block and the name ↔ id map tools resolve arguments with (§3.1). Read-only SQL
 * over {@code category}; sorted by name ignoring case, in Java, as the category API sorts them.
 */
@Component
public class CategoryNamesQuery {

    private final JdbcClient jdbc;

    public CategoryNamesQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Name → id. */
    @Transactional(readOnly = true)
    public Map<String, Long> load() {
        return jdbc.sql("SELECT id, name FROM category").query((rs, row) -> Map.entry(rs.getString("name"),
                rs.getLong("id"))).list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                Map.Entry::getValue));
    }

    /** The names, sorted ignoring case. */
    public static List<String> sorted(Map<String, Long> names) {
        return names.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
