package com.finalysis.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.finalysis.vault.VaultTestSupport.Result;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The vault database's startup check, {@code db-check.sh} (docs/plans/phase-8.md §4.4, §10 "Startup checks"), on
 * fakes: a fake Postgres bin directory whose tools print canned versions, launchd job files and Postgres.app settings
 * in temporary directories, and a port this test holds open. No disk image, no real Postgres. macOS only (it reads
 * plists with plutil).
 */
class VaultDbCheckTest {

    private Path root;
    private Path vault;
    private Path bin;
    private Path launchd;
    private Path prefs;
    private int freePort;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(VaultTestSupport.onMacWithHdiutil(), "db-check.sh runs on macOS");
        root = Files.createTempDirectory("finalysis db-check ").toRealPath();
        vault = Files.createDirectory(root.resolve("vault"));
        launchd = Files.createDirectory(root.resolve("launchd"));
        prefs = root.resolve("postgresapp.plist");
        Files.writeString(prefs, VaultTestSupport.emptyPlist());
        bin = fakePostgres("17.11", "0.8.6");
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            freePort = socket.getLocalPort();
        }
    }

    @AfterEach
    void tearDown() {
        if (root != null) {
            VaultTestSupport.deleteRecursively(root);
        }
    }

    @Test
    void okWhenEverythingIsInPlace() {
        assertThat(check().out().strip()).isEqualTo("OK");
    }

    @Test
    void postgresMissing() throws IOException {
        Path empty = Files.createDirectories(root.resolve("nothing/bin"));
        assertThat(check(empty).out().strip()).isEqualTo("NOT_READY postgres-missing required=17");
    }

    @Test
    void postgresWrongVersionNamesInstalledAndRequired() throws IOException {
        Path old = fakePostgres("16.4", "0.8.6");
        assertThat(check(old).out().strip())
                .isEqualTo("NOT_READY postgres-wrong-version installed=16.4 required=17");
    }

    @Test
    void anyPostgres17MinorPasses() throws IOException {
        assertThat(check(fakePostgres("17.2", "0.8.6")).out().strip()).isEqualTo("OK");
    }

    @Test
    void pgvectorMissing() throws IOException {
        Path noVector = fakePostgres("17.11", null);
        assertThat(check(noVector).out().strip()).isEqualTo("NOT_READY pgvector-missing required=0.8.6");
    }

    @Test
    void pgvectorWrongVersionNamesInstalledAndRequired() throws IOException {
        Path newer = fakePostgres("17.11", "0.8.7");
        assertThat(check(newer).out().strip())
                .isEqualTo("NOT_READY pgvector-wrong-version installed=0.8.7 required=0.8.6");
    }

    @Test
    void launchdJobNamingTheVaultIsAConflictInXmlAndBinaryForm() throws IOException {
        Path job = launchd.resolve("com.example.vault-db.plist");
        Files.writeString(job, jobPlist(vault.resolve("mnt/pgdata").toString()));
        assertThat(check().out().strip()).isEqualTo("NOT_READY launchd-conflict item=com.example.vault-db.plist");

        VaultTestSupport.exec("/usr/bin/plutil", "-convert", "binary1", job.toString());
        assertThat(Files.readString(job, java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("bplist");
        assertThat(check().out().strip()).isEqualTo("NOT_READY launchd-conflict item=com.example.vault-db.plist");
    }

    @Test
    void launchdJobsElsewhereAreNotAConflict() throws IOException {
        Files.writeString(launchd.resolve("homebrew.mxcl.postgresql@17.plist"),
                jobPlist("/usr/local/var/postgresql@17"));
        Files.writeString(launchd.resolve("notes.txt"), vault.toString());
        assertThat(check().out().strip()).isEqualTo("OK");
    }

    @Test
    void postgresAppServerUsingTheVaultIsAConflict() throws IOException {
        Files.writeString(prefs, postgresAppPrefs(vault.resolve("mnt/pgdata").toString()));
        assertThat(check().out().strip()).isEqualTo("NOT_READY postgresapp-conflict item=com.postgresapp.Postgres2");
    }

    @Test
    void postgresAppDefaultServerIsNotAConflict() throws IOException {
        Files.writeString(prefs, postgresAppPrefs(
                System.getProperty("user.home") + "/Library/Application Support/Postgres/var-17"));
        assertThat(check().out().strip()).isEqualTo("OK");
    }

    @Test
    void portInUse() throws IOException {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Result result = run(bin, taken.getLocalPort());
            assertThat(result.out().strip()).isEqualTo("NOT_READY port-in-use item=" + taken.getLocalPort());
        }
    }

    @Test
    void outputNeverNamesAPath() throws IOException {
        Files.writeString(launchd.resolve("x.plist"), jobPlist(vault.toString()));
        Result result = check();
        assertThat(result.all()).doesNotContain(vault.toString()).doesNotContain(root.toString());
    }

    @Test
    void argumentsAreChecked() {
        assertThat(VaultTestSupport.run(root, "db-check.sh", null, Map.of(), vault.toString()).exit()).isEqualTo(1);
        assertThat(VaultTestSupport.run(root, "db-check.sh", null, Map.of(), vault.toString(), "relative/bin", "55432")
                .err()).contains("absolute path");
        assertThat(VaultTestSupport.run(root, "db-check.sh", null, Map.of(), vault.toString(), bin.toString(), "80")
                .err()).contains("1024 to 65535");
    }

    private Result check() {
        return run(bin, freePort);
    }

    private Result check(Path binDir) {
        return run(binDir, freePort);
    }

    private Result run(Path binDir, int port) {
        Result result = VaultTestSupport.run(root, "db-check.sh", null,
                Map.of("FINALYSIS_LAUNCHD_DIRS", launchd.toString(), "FINALYSIS_POSTGRESAPP_PREFS", prefs.toString()),
                vault.toString(), binDir.toString(), String.valueOf(port));
        assertThat(result.exit()).as(result.all()).isZero();
        return result;
    }

    /** A bin directory whose postgres prints {@code version}; pgvector's control file names {@code vector}. */
    private Path fakePostgres(String version, String vector) throws IOException {
        Path base = Files.createTempDirectory(root, "pg-" + version + "-");
        Path dir = Files.createDirectory(base.resolve("bin"));
        Path share = Files.createDirectories(base.resolve("share/postgresql/extension"));
        script(dir.resolve("postgres"), "echo 'postgres (PostgreSQL) " + version + " (Postgres.app)'");
        script(dir.resolve("pg_config"), "echo '" + base.resolve("share/postgresql") + "'");
        script(dir.resolve("initdb"), "exit 0");
        script(dir.resolve("pg_ctl"), "exit 0");
        if (vector != null) {
            Files.writeString(share.resolve("vector.control"), """
                    # vector extension
                    comment = 'vector data type and ivfflat and hnsw access methods'
                    default_version = '%s'
                    module_pathname = '$libdir/vector'
                    relocatable = true
                    """.formatted(vector));
        }
        return dir;
    }

    private static void script(Path path, String body) throws IOException {
        Files.writeString(path, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }

    private static String jobPlist(String path) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                  <key>Label</key><string>com.example.db</string>
                  <key>ProgramArguments</key><array>
                    <string>/opt/pg/bin/postgres</string><string>-D</string><string>%s</string>
                  </array>
                  <key>RunAtLoad</key><true/>
                </dict></plist>
                """.formatted(path);
    }

    private static String postgresAppPrefs(String dataDirectory) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                  <key>Servers</key><array><dict>
                    <key>Name</key><string>PostgreSQL 17</string>
                    <key>Port</key><integer>5432</integer>
                    <key>VarPath</key><string>%s</string>
                  </dict></array>
                </dict></plist>
                """.formatted(dataDirectory);
    }
}
