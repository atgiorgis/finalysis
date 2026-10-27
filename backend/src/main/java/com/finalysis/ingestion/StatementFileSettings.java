package com.finalysis.ingestion;

import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What happens to a statement file after a successful import (docs/plans/phase-8.md §7): the default the Import tab
 * applies, or asks about, kept in {@code app_setting} under {@code statements.after-import}. {@code ASK} (the default)
 * offers the four choices each time.
 */
@Component
public class StatementFileSettings {

    /** The choices: ask each time, keep a copy in the vault, delete the original, both, or leave it. */
    public enum AfterImport { ASK, KEEP, DELETE, BOTH, LEAVE }

    static final String KEY = "statements.after-import";

    private final JdbcTemplate jdbc;

    StatementFileSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AfterImport afterImport() {
        List<String> stored = jdbc.queryForList("SELECT value FROM app_setting WHERE key = ?", String.class, KEY);
        if (stored.isEmpty()) {
            return AfterImport.ASK;
        }
        try {
            return AfterImport.valueOf(stored.getFirst().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AfterImport.ASK;
        }
    }

    public void setAfterImport(AfterImport choice) {
        jdbc.update("""
                INSERT INTO app_setting (key, value) VALUES (?, ?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""", KEY, choice.name());
    }
}
