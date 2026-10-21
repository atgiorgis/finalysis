package com.finalysis.chat.outbound;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads every account's name, institution and last four for {@link QuestionRedactor}. Local only; read-only SQL. */
@Component
public class AccountIdentityQuery {

    private final JdbcClient jdbc;

    public AccountIdentityQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<AccountIdentity> load() {
        return jdbc.sql("SELECT id, name, institution, last_four FROM account ORDER BY id")
                .query((rs, row) -> new AccountIdentity(rs.getLong("id"), rs.getString("name"),
                        rs.getString("institution"), rs.getString("last_four")))
                .list();
    }
}
