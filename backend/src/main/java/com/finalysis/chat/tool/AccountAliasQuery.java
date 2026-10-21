package com.finalysis.chat.tool;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the accounts for {@link AccountAliases}: id, type and whether closed. Its own read-only SQL, so chat depends on
 * no class of the {@code account} feature (docs/plans/phase-6.md §9). Names, institutions and last fours are not read
 * here.
 */
@Component
public class AccountAliasQuery {

    private final JdbcClient jdbc;

    public AccountAliasQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public AccountAliases load() {
        return AccountAliases.of(jdbc.sql("SELECT id, type, closed_on IS NOT NULL AS closed FROM account ORDER BY id")
                .query((rs, row) -> new AccountAliases.Account(rs.getLong("id"), rs.getString("type"),
                        rs.getBoolean("closed")))
                .list());
    }
}
