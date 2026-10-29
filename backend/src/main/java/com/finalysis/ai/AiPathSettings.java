package com.finalysis.ai;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The user's path in {@code app_setting} (docs/plans/phase-9.md §3.1): {@code ai.path}, {@code ai.selfhost.name}
 * (the server's Tailscale name) and {@code ai.selfhost.region} (stored for 9c; nothing reads it yet). Inside the
 * vault, so read at unlock and written by a switch, never while locked.
 */
@Component
public class AiPathSettings {

    static final String PATH = "ai.path";
    static final String NAME = "ai.selfhost.name";
    static final String REGION = "ai.selfhost.region";

    /**
     * A host name: dot-separated labels of letters, digits and inner hyphens, at most 253 characters. Never a URL: no
     * scheme, port, path, user info, query or space can match.
     */
    private static final Pattern SERVER_NAME = Pattern.compile(
            "(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");

    /** An AWS region id ({@code us-east-1}): lowercase letters, digits and hyphens. */
    private static final Pattern REGION_ID = Pattern.compile("[a-z]{2}(?:-[a-z0-9]+)+");

    /**
     * What the user chose.
     *
     * @param selfhostName   the server's Tailscale name; null unless set
     * @param selfhostRegion its AWS region (9c); null unless set
     */
    public record Selection(AiPath path, String selfhostName, String selfhostRegion) {
    }

    private final JdbcTemplate jdbc;

    public AiPathSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static boolean validServerName(String name) {
        return name != null && SERVER_NAME.matcher(name).matches();
    }

    public static boolean validRegion(String region) {
        return region != null && region.length() <= 32 && REGION_ID.matcher(region).matches();
    }

    /** The stored selection, or {@code defaultPath} for a vault with none (a fresh vault). */
    public Selection read(AiPath defaultPath) {
        Map<String, String> values = jdbc.queryForList(
                        "SELECT key, value FROM app_setting WHERE key IN (?, ?, ?)", PATH, NAME, REGION).stream()
                .collect(Collectors.toMap(row -> (String) row.get("key"), row -> (String) row.get("value")));
        return resolve(values, defaultPath);
    }

    /**
     * The selection the stored values give: {@code defaultPath} when no path is stored; a value that isn't a path, or a
     * name or region that doesn't validate, is ignored rather than trusted.
     */
    public static Selection resolve(Map<String, String> values, AiPath defaultPath) {
        AiPath path = defaultPath;
        String stored = values.get(PATH);
        if (stored != null) {
            try {
                path = AiPath.valueOf(stored);
            } catch (IllegalArgumentException e) {
                path = defaultPath;
            }
        }
        String name = values.get(NAME);
        String region = values.get(REGION);
        return new Selection(path, validServerName(name) ? name : null, validRegion(region) ? region : null);
    }

    public void save(Selection selection) {
        upsert(PATH, selection.path().name());
        if (selection.selfhostName() != null) {
            upsert(NAME, selection.selfhostName());
        }
        if (selection.selfhostRegion() != null) {
            upsert(REGION, selection.selfhostRegion());
        }
    }

    private void upsert(String key, String value) {
        jdbc.update("""
                INSERT INTO app_setting (key, value) VALUES (?, ?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""", key, value);
    }
}
