package com.finalysis.vault;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Postgres.app's PostgreSQL 17 on the mounted vault, through {@code db-check.sh}, {@code db-init.sh},
 * {@code db-start.sh} and {@code db-stop.sh} (docs/plans/phase-8.md §4.4). The superuser password lives in
 * {@code <mount>/secrets/db-password}, inside the vault, and is read only after unlock.
 */
final class NativeDatabaseRuntime implements DatabaseRuntime {

    private final VaultScripts scripts;
    private final VaultProperties properties;

    NativeDatabaseRuntime(VaultScripts scripts, VaultProperties properties) {
        this.scripts = scripts;
        this.properties = properties;
    }

    @Override
    public ReadyCheck check() {
        VaultScripts.Result result = scripts.run("db-check.sh", null, dir(), bin(), port());
        if (result.exit() != 0) {
            return new ReadyCheck(false, "check-failed", null, null, null);
        }
        return ReadyCheck.parse(result.out());
    }

    @Override
    public void init(char[] password) {
        if (scripts.run("db-init.sh", VaultScripts.bytes(password), dir(), bin(), port()).exit() != 0) {
            throw new DatabaseException("db-init.sh failed");
        }
    }

    @Override
    public Connection start() {
        if (scripts.run("db-start.sh", null, dir(), bin(), port()).exit() != 0) {
            throw new DatabaseException("db-start.sh failed");
        }
        Path secret = properties.mountPoint().resolve("secrets").resolve("db-password");
        try {
            String password = Files.readString(secret, UTF_8).strip();
            return new Connection("jdbc:postgresql://127.0.0.1:" + properties.dbPort() + "/finalysis", "finalysis",
                    password);
        } catch (IOException e) {
            throw new DatabaseException("the database password could not be read");
        }
    }

    @Override
    public Stopped stop() {
        int exit = scripts.run("db-stop.sh", null, dir(), bin()).exit();
        return switch (exit) {
            case 0 -> Stopped.FAST;
            case 3 -> Stopped.IMMEDIATE;
            default -> throw new DatabaseException("db-stop.sh failed");
        };
    }

    private String dir() {
        return properties.vaultDir().toString();
    }

    private String bin() {
        return properties.postgresBin().toString();
    }

    private String port() {
        return String.valueOf(properties.dbPort());
    }
}
