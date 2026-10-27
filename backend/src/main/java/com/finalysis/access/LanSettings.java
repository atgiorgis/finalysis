package com.finalysis.access;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The phone-access setting (docs/plans/phase-8.md §9.1) in {@code app_setting}: on or off, the address and the port.
 * Off by default. Read only while the database is open (unlocked).
 */
class LanSettings {

    record Setting(boolean enabled, String address, int port) {
    }

    static final String ENABLED = "access.lan.enabled";
    static final String ADDRESS = "access.lan.address";
    static final String PORT = "access.lan.port";

    private final JdbcTemplate jdbc;
    private final int defaultPort;

    LanSettings(JdbcTemplate jdbc, int defaultPort) {
        this.jdbc = jdbc;
        this.defaultPort = defaultPort;
    }

    Setting read() {
        Map<String, String> values = jdbc.queryForList(
                        "SELECT key, value FROM app_setting WHERE key IN (?, ?, ?)", ENABLED, ADDRESS, PORT).stream()
                .collect(Collectors.toMap(row -> (String) row.get("key"), row -> (String) row.get("value")));
        int port;
        try {
            port = Integer.parseInt(values.getOrDefault(PORT, String.valueOf(defaultPort)));
        } catch (NumberFormatException e) {
            port = defaultPort;
        }
        return new Setting("true".equals(values.get(ENABLED)), values.get(ADDRESS), port);
    }

    void save(Setting setting) {
        for (List<String> pair : List.of(List.of(ENABLED, String.valueOf(setting.enabled())),
                List.of(PORT, String.valueOf(setting.port())))) {
            upsert(pair.get(0), pair.get(1));
        }
        if (setting.address() != null) {
            upsert(ADDRESS, setting.address());
        }
    }

    private void upsert(String key, String value) {
        jdbc.update("""
                INSERT INTO app_setting (key, value) VALUES (?, ?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""", key, value);
    }
}
