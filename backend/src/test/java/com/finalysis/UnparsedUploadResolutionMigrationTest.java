package com.finalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.support.IntegrationTest;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V11's backfill of {@code unparsed_upload.resolution}: run against a scratch database migrated to V10
 * and filled with an open, a resolved, a dismissed and an orphaned (statement deleted) upload, then
 * migrated to V11. The shared test database is never touched.
 */
@IntegrationTest
class UnparsedUploadResolutionMigrationTest {

    private static final String DATABASE = "finalysis_v11_backfill";

    private final JdbcTemplate shared;
    private final PostgreSQLContainer postgres;
    private DriverManagerDataSource scratch;

    UnparsedUploadResolutionMigrationTest(JdbcTemplate shared, PostgreSQLContainer postgres) {
        this.shared = shared;
        this.postgres = postgres;
    }

    @BeforeEach
    void createScratchDatabase() {
        shared.execute("DROP DATABASE IF EXISTS " + DATABASE);
        shared.execute("CREATE DATABASE " + DATABASE);
        String url = postgres.getJdbcUrl().replaceFirst("/" + postgres.getDatabaseName() + "(?=\\?|$)", "/" + DATABASE);
        scratch = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
    }

    @AfterEach
    void dropScratchDatabase() {
        shared.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
    }

    @Test
    void backfillsEveryExistingUpload() {
        migrateTo("10");
        JdbcTemplate jdbc = new JdbcTemplate(scratch);
        long account = jdbc.queryForObject("""
                INSERT INTO account (name, institution, type, last_four)
                VALUES ('Test', 'Northfield Bank', 'CHECKING', '1001') RETURNING id""", Long.class);
        long kept = statement(jdbc, account, "hash-kept");
        long deleted = statement(jdbc, account, "hash-deleted");
        long open = upload(jdbc, account, "a");
        long resolved = upload(jdbc, account, "b");
        long dismissed = upload(jdbc, account, "c");
        long orphaned = upload(jdbc, account, "d");
        jdbc.update("UPDATE unparsed_upload SET resolved_at = now(), resolved_statement_id = ? WHERE id = ?", kept, resolved);
        jdbc.update("UPDATE unparsed_upload SET resolved_at = now() WHERE id = ?", dismissed);
        jdbc.update("UPDATE unparsed_upload SET resolved_at = now(), resolved_statement_id = ? WHERE id = ?", deleted,
                orphaned);
        jdbc.update("DELETE FROM statement WHERE id = ?", deleted);

        migrateTo("11");

        assertThat(resolution(jdbc, open)).isNull();
        assertThat(resolution(jdbc, resolved)).isEqualTo("RESOLVED");
        assertThat(resolution(jdbc, dismissed)).isEqualTo("DISMISSED");
        // Indistinguishable from a dismissal under V8: it read as DISMISSED before and still does.
        assertThat(resolution(jdbc, orphaned)).isEqualTo("DISMISSED");
        assertThat(jdbc.queryForMap("SELECT resolved_at IS NOT NULL AS resolved, resolved_statement_id "
                + "FROM unparsed_upload WHERE id = ?", resolved))
                .isEqualTo(Map.of("resolved", true, "resolved_statement_id", kept));
    }

    private void migrateTo(String version) {
        Flyway.configure().dataSource(scratch).target(version).load().migrate();
    }

    private static long statement(JdbcTemplate jdbc, long account, String hash) {
        return jdbc.queryForObject("""
                INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash, parser)
                VALUES (?, DATE '2026-04-01', DATE '2026-04-30', 'a.pdf', ?, 'fixture') RETURNING id
                """, Long.class, account, hash);
    }

    private static long upload(JdbcTemplate jdbc, long account, String hashChar) {
        return jdbc.queryForObject("""
                INSERT INTO unparsed_upload (account_id, file_sha256, file_name, reason)
                VALUES (?, ?, 'a.pdf', 'UNRECOGNIZED_LAYOUT') RETURNING id
                """, Long.class, account, hashChar.repeat(64));
    }

    private static String resolution(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT resolution FROM unparsed_upload WHERE id = ?", String.class, id);
    }
}
