package com.finalysis.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.finalysis.vault.VaultTestSupport.ArgumentWatch;
import com.finalysis.vault.VaultTestSupport.Result;
import com.finalysis.vault.VaultTestSupport.TempVault;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The vault's database on a native Postgres.app PostgreSQL 17 inside a throwaway encrypted image
 * (docs/plans/phase-8.md §4.4, §4.6, §10 "Vault database (native)"). Opt-in: {@code ./mvnw test -Pvault-it}
 * (§16, decision 4). Skipped, through an assumption, when {@code db-check.sh} doesn't find PostgreSQL 17 with
 * pgvector 0.8.6 at the Postgres.app path.
 *
 * <p>The case Docker failed (§4.8): five lock and unlock cycles at the same mount point, each unmount plain.
 */
@Tag("vault-it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VaultDatabaseIT {

    private TempVault vault;
    private String password;
    private String port;
    private boolean passwordOnACommandLine;
    private int argumentSamples;

    @BeforeAll
    void setUpVault() throws Exception {
        assumeTrue(VaultTestSupport.onMacWithHdiutil(), "the vault needs macOS");
        vault = new TempVault();
        port = String.valueOf(freePort());
        String bin = VaultTestSupport.POSTGRES_APP_BIN.toString();
        String check = vault.run("db-check.sh", null, vault.dir.toString(), bin, port).out().strip();
        assumeTrue("OK".equals(check), "Postgres.app's PostgreSQL 17 with pgvector 0.8.6 is needed: " + check);

        password = VaultTestSupport.syntheticPassword();
        try (ArgumentWatch watch = new ArgumentWatch(vault.key, password)) {
            expectZero(vault.create("512m"));
            expectZero(vault.mount());
            expectZero(vault.run("db-init.sh", password + "\n", vault.dir.toString(), bin, port));
            expectZero(vault.run("db-start.sh", null, vault.dir.toString(), bin, port));
            try (Connection c = connect()) {
                c.createStatement().execute("SELECT 1");
            }
            passwordOnACommandLine = watch.secretSeen();
            argumentSamples = watch.samples();
        }
    }

    @AfterAll
    void tearDown() {
        if (vault != null) {
            vault.close();
        }
    }

    @Test
    @Order(1)
    void theKeyAndPasswordNeverReachACommandLine() {
        assertThat(argumentSamples).isPositive();
        assertThat(passwordOnACommandLine).isFalse();
        assertThat(VaultTestSupport.exec("/bin/ps", "-axww", "-o", "args=")).doesNotContain(password);
    }

    @Test
    @Order(2)
    void theRunningServerReportsTheVaultSettings() throws SQLException {
        try (Connection c = connect()) {
            assertThat(show(c, "server_version")).startsWith("17.");
            assertThat(show(c, "listen_addresses")).isEqualTo("127.0.0.1");
            assertThat(show(c, "port")).isEqualTo(port);
            assertThat(show(c, "unix_socket_directories")).isEmpty();
            assertThat(show(c, "password_encryption")).isEqualTo("scram-sha-256");
            assertThat(show(c, "data_checksums")).isEqualTo("on");
            assertThat(show(c, "logging_collector")).isEqualTo("on");
            assertThat(show(c, "log_directory")).isEqualTo("log");
            assertThat(show(c, "log_error_verbosity")).isEqualTo("terse");
            assertThat(show(c, "log_min_error_statement")).isEqualTo("panic");
            assertThat(show(c, "log_statement")).isEqualTo("none");
            assertThat(show(c, "log_min_messages")).isEqualTo("warning");
            assertThat(show(c, "data_directory")).isEqualTo(vault.mnt().resolve("pgdata").toString());
            assertThat(query(c, "SELECT datlocprovider::text || ' ' || datlocale FROM pg_database "
                    + "WHERE datname = 'finalysis'")).isEqualTo("i en-US");
            assertThat(query(c, "SELECT lower('ÉCLAIR CAFÉ')")).isEqualTo("éclair café");
        }
        assertThat(Files.exists(Path.of("/tmp/.s.PGSQL." + port))).isFalse();
    }

    @Test
    @Order(3)
    void theAppsMigrationsRunOnTheVault() throws SQLException {
        Flyway flyway = Flyway.configure().dataSource(url("127.0.0.1"), "finalysis", password)
                .locations("classpath:db/migration").load();

        flyway.migrate();

        assertThat(flyway.info().pending()).isEmpty();
        try (Connection c = connect()) {
            assertThat(query(c, "SELECT extversion FROM pg_extension WHERE extname = 'vector'")).isEqualTo("0.8.6");
            assertThat(query(c, "SELECT count(*) FROM category")).isNotEqualTo("0");
        }
    }

    @Test
    @Order(4)
    void onlyLoopbackTcpWithTheRightPassword() throws IOException {
        assertThatThrownBy(() -> DriverManager.getConnection(url("127.0.0.1"), "finalysis", "wrong-password").close())
                .isInstanceOf(SQLException.class).hasMessageContaining("password authentication failed");
        assertThatThrownBy(() -> DriverManager.getConnection(url("[::1]"), "finalysis", password).close())
                .isInstanceOf(SQLException.class);
        assertThat(Files.readAllLines(vault.mnt().resolve("pgdata/pg_hba.conf")).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#"))).containsExactly(
                        "host all all 127.0.0.1/32 scram-sha-256");
        assertThat(Files.getPosixFilePermissions(vault.mnt().resolve("secrets/db-password")))
                .hasSize(2); // rw-------
    }

    @Test
    @Order(5)
    void logsStayInsideTheVaultAndCarryNoRowValues() throws Exception {
        String marker = "LOGMARKER" + VaultTestSupport.syntheticKey();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE log_probe (note text PRIMARY KEY)");
            s.execute("INSERT INTO log_probe VALUES ('" + marker + "')");
            // The key's value would be in the DETAIL line ("Key (note)=(...) already exists"), which terse verbosity
            // leaves out. (A primary message can still quote a value, e.g. "invalid input syntax"; such lines stay
            // inside the vault, encrypted at rest.)
            assertThatThrownBy(() -> s.execute("INSERT INTO log_probe VALUES ('" + marker + "')"))
                    .hasMessageContaining("duplicate key");
        }

        Path logs = vault.mnt().resolve("pgdata/log");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (!logMentions(logs, "duplicate key") && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
        assertThat(logMentions(logs, "duplicate key")).as("the violation reached the server log").isTrue();
        assertThat(VaultTestSupport.anyFileContains(logs, marker)).isFalse();
        assertThat(VaultTestSupport.anyFileContains(vault.mnt().resolve("pglog"), marker)).isFalse();
        assertThat(VaultTestSupport.anyFileContains(logs, password)).isFalse();
        assertThat(VaultTestSupport.anyFileContains(vault.mnt().resolve("pglog"), password)).isFalse();
    }

    @Test
    @Order(6)
    void fiveLockAndUnlockCyclesAtOneMountPointEachWithAPlainUnmount() throws Exception {
        String bin = VaultTestSupport.POSTGRES_APP_BIN.toString();
        String marker = "ROWMARKER" + VaultTestSupport.syntheticKey();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS vector");
            s.execute("CREATE TABLE cycle_probe (id int PRIMARY KEY, note text, v vector(3))");
            s.execute("INSERT INTO cycle_probe SELECT g, '" + marker + "' || g, '[1,2,3]' "
                    + "FROM generate_series(1, 2000) g");
        }

        List<String> cycles = new ArrayList<>();
        for (int cycle = 1; cycle <= 5; cycle++) {
            Result stopped = vault.run("db-stop.sh", null, vault.dir.toString(), bin);
            Result unmounted = vault.unmount();
            cycles.add("cycle " + cycle + ": " + stopped.out().strip() + "; " + unmounted.out().strip());
            assertThat(stopped.exit()).as(cycles.getLast()).isZero();
            assertThat(stopped.out()).as(cycles.getLast()).contains("db-stop: done").doesNotContain("immediate");
            assertThat(unmounted.exit()).as(cycles.getLast() + unmounted.err()).isZero();
            assertThat(unmounted.out()).as(cycles.getLast()).doesNotContain("forced");
            assertThat(vault.status()).isEqualTo("UNMOUNTED");
            try (Stream<Path> entries = Files.list(vault.mnt())) {
                assertThat(entries).isEmpty();
            }

            expectZero(vault.mount());
            expectZero(vault.run("db-start.sh", null, vault.dir.toString(), bin, port));
            try (Connection c = connect()) {
                assertThat(query(c, "SELECT count(*) FROM cycle_probe")).as(cycles.getLast()).isEqualTo("2000");
            }
        }

        expectZero(vault.run("db-stop.sh", null, vault.dir.toString(), bin));
        Result last = vault.unmount();
        assertThat(last.exit()).as(last.all()).isZero();
        assertThat(VaultTestSupport.anyFileContains(vault.bundle(), marker)).isFalse();
        assertThat(VaultTestSupport.anyFileContains(vault.bundle(), password)).isFalse();

        Result refused = vault.run("db-start.sh", null, vault.dir.toString(), bin, port);
        assertThat(refused.exit()).isEqualTo(1);
        assertThat(refused.err()).contains("not mounted");
        assertThatThrownBy(() -> connect().close()).isInstanceOf(SQLException.class);
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url("127.0.0.1"), "finalysis", password);
    }

    private String url(String host) {
        return "jdbc:postgresql://" + host + ":" + port + "/finalysis?connectTimeout=5";
    }

    private static String show(Connection c, String setting) throws SQLException {
        return query(c, "SHOW " + setting);
    }

    private static String query(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static boolean logMentions(Path logs, String text) throws IOException {
        try (Stream<Path> files = Files.list(logs)) {
            for (Path file : files.toList()) {
                if (Files.readString(file).contains(text)) {
                    return true;
                }
            }
        }
        return false;
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
