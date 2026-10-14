package com.finalysis.categorize;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads the {@link RedactionContext} from the accounts and txns. Local only; nothing here is sent or logged. */
@Component
public class RedactionContextQuery {

    private final JdbcTemplate jdbc;

    public RedactionContextQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public RedactionContext load() {
        Set<String> lastFours = new HashSet<>();
        List<String> phrases = new ArrayList<>();
        jdbc.query("SELECT name, institution, last_four FROM account", rs -> {
            phrases.add(rs.getString("name"));
            phrases.add(rs.getString("institution"));
            lastFours.add(rs.getString("last_four"));
        });
        Set<String> counterparties = new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT counterparty FROM txn WHERE counterparty IS NOT NULL", String.class));
        return new RedactionContext(lastFours, phrases.stream().filter(p -> p != null && !p.isBlank()).toList(),
                counterparties);
    }
}
