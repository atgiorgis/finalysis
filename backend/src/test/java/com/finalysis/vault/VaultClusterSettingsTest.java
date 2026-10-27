package com.finalysis.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins what {@code db-init.sh} gives the vault's cluster (docs/plans/phase-8.md §4.4): loopback TCP on its own port,
 * no Unix socket, SCRAM, logs inside the vault with the terse flags, and a {@code pg_hba.conf} of one line. A change
 * here is a security decision, not a tidy-up. {@code VaultDatabaseIT} checks the running server reports the same.
 */
class VaultClusterSettingsTest {

    @Test
    void postgresqlConfHasExactlyTheVaultSettings() throws IOException {
        Map<String, String> settings = new LinkedHashMap<>();
        for (String line : Files.readAllLines(VaultTestSupport.SCRIPTS.resolve("postgresql.vault.conf"))) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            String[] parts = text.split("\\s*=\\s*", 2);
            assertThat(settings.put(parts[0], parts[1])).as("set twice: " + parts[0]).isNull();
        }

        assertThat(settings).containsExactly(
                Map.entry("listen_addresses", "'127.0.0.1'"),
                Map.entry("port", "@PORT@"),
                Map.entry("unix_socket_directories", "''"),
                Map.entry("password_encryption", "scram-sha-256"),
                Map.entry("logging_collector", "on"),
                Map.entry("log_directory", "'log'"),
                Map.entry("log_filename", "'postgresql-%a.log'"),
                Map.entry("log_truncate_on_rotation", "on"),
                Map.entry("log_rotation_age", "1d"),
                Map.entry("log_rotation_size", "0"),
                Map.entry("log_error_verbosity", "terse"),
                Map.entry("log_min_error_statement", "panic"),
                Map.entry("log_statement", "none"),
                Map.entry("log_min_messages", "warning"));
    }

    @Test
    void pgHbaAllowsOnlyLoopbackTcpWithScram() throws IOException {
        List<String> rules = Files.readAllLines(VaultTestSupport.SCRIPTS.resolve("pg_hba.vault.conf")).stream()
                .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();

        assertThat(rules).containsExactly("host all all 127.0.0.1/32 scram-sha-256");
    }

    @Test
    void initdbAndPgCtlKeepSecretsAndOutputInsideTheVault() throws IOException {
        String init = Files.readString(VaultTestSupport.SCRIPTS.resolve("db-init.sh"));
        assertThat(init)
                .contains("--pwfile=/dev/stdin", "--auth=scram-sha-256", "--username=finalysis", "--encoding=UTF8",
                        "--locale-provider=icu --icu-locale=en-US", "--data-checksums",
                        ">\"$mnt/pglog/initdb.log\"", "\"$mnt/secrets/db-password\"")
                .doesNotContain("PGPASSWORD");

        String start = Files.readString(VaultTestSupport.SCRIPTS.resolve("db-start.sh"));
        assertThat(start).contains("-l \"$mnt/pglog/pg_ctl.log\" -w");

        String stop = Files.readString(VaultTestSupport.SCRIPTS.resolve("db-stop.sh"));
        assertThat(stop).contains("-m fast -w -t 30");
    }

    @Test
    void everyScriptRestrictsItsPathAndRefusesExtraArguments() throws IOException {
        String lib = Files.readString(VaultTestSupport.SCRIPTS.resolve("lib.sh"));
        assertThat(lib).contains("export PATH=/usr/bin:/bin:/usr/sbin:/sbin", "umask 077", "set -euo pipefail");
        for (String script : List.of("create.sh", "mount.sh", "unmount.sh", "status.sh", "db-check.sh", "db-init.sh",
                "db-start.sh", "db-stop.sh", "migrate-plain-to-vault.sh", "db-refresh-collation.sh")) {
            String text = Files.readString(VaultTestSupport.SCRIPTS.resolve(script));
            assertThat(text).as(script).contains("source \"$(dirname \"${BASH_SOURCE[0]}\")/lib.sh\"")
                    .containsPattern("\\[\\[ \\$# -eq \\d \\]\\] \\|\\| refuse");
            assertThat(Files.isExecutable(VaultTestSupport.SCRIPTS.resolve(script))).as(script).isTrue();
        }
    }
}
