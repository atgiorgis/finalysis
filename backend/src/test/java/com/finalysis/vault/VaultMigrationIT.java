package com.finalysis.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.SampleDataImport;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import com.finalysis.vault.VaultTestSupport.Result;
import com.finalysis.vault.VaultTestSupport.TempVault;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dev migration, {@code migrate-plain-to-vault.sh} (docs/plans/phase-8.md §4.5, §10 "Dev migration"), from the
 * tests' Testcontainers database holding the synthetic sample statements, imported for real, into a throwaway vault
 * on Postgres.app. Opt-in with {@code -Pvault-it}; skipped without Postgres.app's PostgreSQL 17 and pgvector 0.8.6.
 */
@Tag("vault-it")
@IntegrationTest
@AutoConfigureMockMvc
class VaultMigrationIT {

    private final ImportTestSupport support;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final PostgreSQLContainer source;

    VaultMigrationIT(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper,
                     PostgreSQLContainer source) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.source = source;
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @Test
    void copiesEveryTableWithTheSameRowCountsAndPrintsCountsOnly() throws Exception {
        assumeTrue(VaultTestSupport.onMacWithHdiutil(), "the vault needs macOS");
        Timer timer = new Timer();
        new SampleDataImport(support, jsonMapper).load();
        timer.lap("sample import into the source database");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM txn", Long.class)).isGreaterThan(100);

        try (TempVault vault = new TempVault()) {
            String bin = VaultTestSupport.POSTGRES_APP_BIN.toString();
            String port = String.valueOf(freePort());
            String check = vault.run("db-check.sh", null, vault.dir.toString(), bin, port).out().strip();
            assumeTrue("OK".equals(check), "Postgres.app's PostgreSQL 17 with pgvector 0.8.6 is needed: " + check);
            String password = VaultTestSupport.syntheticPassword();
            expectZero(vault.create("512m"));
            expectZero(vault.mount());
            expectZero(vault.run("db-init.sh", password + "\n", vault.dir.toString(), bin, port));
            expectZero(vault.run("db-start.sh", null, vault.dir.toString(), bin, port));
            timer.lap("vault: create, mount, initdb, start");
            String url = "jdbc:postgresql://127.0.0.1:" + port + "/finalysis";
            Flyway.configure().dataSource(url, "finalysis", password).locations("classpath:db/migration").load()
                    .migrate();
            timer.lap("Flyway on the vault");

            Result migrated = migrate(vault, bin, port);
            timer.lap("migrate-plain-to-vault.sh");
            // The script's own phases (the dump and restore overlap: one streams into the other).
            migrated.all().lines().filter(line -> line.startsWith("migrate: ")).forEach(timer::note);

            assertThat(migrated.exit()).as(migrated.all()).isZero();
            assertThat(migrated.out()).contains("every table has the same number of rows", "scripts/reset-dev-db.sh")
                    .doesNotContain("DIFFERENT");
            // Counts only: no row value of the sample data reaches the output.
            assertThat(migrated.all()).doesNotContain("PARKSIDE", "SAFEWAY", "SPOTIFY", password);

            Map<String, Long> plain = counts(jdbc);
            Map<String, Long> copied;
            try (Connection c = DriverManager.getConnection(url, "finalysis", password)) {
                copied = counts(c);
                assertThat(sum(c, "SELECT coalesce(sum(amount), 0)::text FROM txn"))
                        .isEqualTo(jdbc.queryForObject("SELECT coalesce(sum(amount), 0)::text FROM txn", String.class));
                assertThat(sum(c, "SELECT count(*) FROM txn WHERE is_transfer")).isEqualTo(
                        String.valueOf(jdbc.queryForObject("SELECT count(*) FROM txn WHERE is_transfer", Long.class)));
            }
            assertThat(copied).isEqualTo(plain);
            timer.lap("compare in Java");
            assertThat(migrated.all()).contains("migrate: dump finished after", "migrate: restore finished after",
                    "migrate: compare took");
            assertThat(copied.get("txn")).isGreaterThan(100);
            assertThat(vault.mnt().resolve("secrets/pgpass")).doesNotExist();

            Result again = migrate(vault, bin, port);
            assertThat(again.exit()).isEqualTo(1);
            assertThat(again.err()).contains("already holds accounts");
        }
    }

    /** Phase times, printed as they finish, so a slow run says where (the step 4 review). */
    private static final class Timer {

        private long last = System.nanoTime();

        void lap(String phase) {
            long now = System.nanoTime();
            note(String.format("%s: %.1f s", phase, (now - last) / 1e9));
            last = now;
        }

        void note(String line) {
            System.out.println("VaultMigrationIT timing: " + line);
        }
    }

    private Result migrate(TempVault vault, String bin, String port) {
        return VaultTestSupport.run(vault.root, "migrate-plain-to-vault.sh", null,
                Map.of("FINALYSIS_MIGRATE_SOURCE_CONTAINER", source.getContainerId()),
                vault.dir.toString(), bin, port);
    }

    private static final String TABLES = "SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
            + "AND tablename <> 'flyway_schema_history' ORDER BY tablename";

    private static Map<String, Long> counts(JdbcTemplate jdbc) {
        Map<String, Long> counts = new TreeMap<>();
        for (String table : jdbc.queryForList(TABLES, String.class)) {
            counts.put(table, jdbc.queryForObject("SELECT count(*) FROM public.\"" + table + "\"", Long.class));
        }
        return counts;
    }

    private static Map<String, Long> counts(Connection c) throws SQLException {
        Map<String, Long> counts = new TreeMap<>();
        List<String> tables = new java.util.ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(TABLES)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        for (String table : tables) {
            counts.put(table, Long.valueOf(sum(c, "SELECT count(*) FROM public.\"" + table + "\"")));
        }
        return counts;
    }

    private static String sum(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void expectZero(Result result) {
        assertThat(result.exit()).as(result.all()).isZero();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
