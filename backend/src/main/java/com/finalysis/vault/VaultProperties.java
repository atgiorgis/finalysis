package com.finalysis.vault;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The vault's settings ({@code finalysis.vault.*}, docs/plans/phase-8.md §4, §5). A bad value fails startup. The paths
 * are bound as text and read with {@link Path#of}: Spring's own String-to-Path conversion goes through resources, which
 * in a servlet container reads a relative or absolute path as one inside the web application.
 *
 * @param enabled         false runs on the plain database ({@code spring.datasource}), unlocked from the start:
 *                        development with synthetic data, and the tests
 * @param dir             the vault directory: the image, the key file and the mount point (absolute)
 * @param pgBin           the PostgreSQL 17 binaries (Postgres.app's, §4.4)
 * @param dbPort          the vault cluster's port on 127.0.0.1 (1024 to 65535)
 * @param scripts         {@code scripts/vault/}, relative to the working directory or absolute
 * @param imageSize       the image's maximum size at creation, like {@code 50g}
 * @param idleLockMinutes the idle lock until the app's own setting exists (1 to 240)
 */
@ConfigurationProperties("finalysis.vault")
public record VaultProperties(@DefaultValue("true") boolean enabled,
                              String dir,
                              @DefaultValue("/Applications/Postgres.app/Contents/Versions/17/bin") String pgBin,
                              @DefaultValue("55432") int dbPort,
                              @DefaultValue("../scripts/vault") String scripts,
                              @DefaultValue("50g") String imageSize,
                              @DefaultValue("15") int idleLockMinutes) {

    public VaultProperties {
        if (enabled) {
            if (dir == null || !Path.of(dir).isAbsolute()) {
                throw new IllegalArgumentException("finalysis.vault.dir must be an absolute path");
            }
            if (pgBin == null || !Path.of(pgBin).isAbsolute()) {
                throw new IllegalArgumentException("finalysis.vault.pg-bin must be an absolute path");
            }
        }
        if (dbPort < 1024 || dbPort > 65535) {
            throw new IllegalArgumentException("finalysis.vault.db-port must be between 1024 and 65535");
        }
        if (imageSize == null || !imageSize.matches("[1-9][0-9]{0,5}[mg]")) {
            throw new IllegalArgumentException("finalysis.vault.image-size must look like 64m or 50g");
        }
        if (idleLockMinutes < 1 || idleLockMinutes > 240) {
            throw new IllegalArgumentException("finalysis.vault.idle-lock-minutes must be between 1 and 240");
        }
    }

    Path vaultDir() {
        return Path.of(dir);
    }

    Path postgresBin() {
        return Path.of(pgBin);
    }

    Path scriptsDir() {
        return Path.of(scripts);
    }

    Path keyFile() {
        return vaultDir().resolve("vault-key.json");
    }

    Path mountPoint() {
        return vaultDir().resolve("mnt");
    }
}
