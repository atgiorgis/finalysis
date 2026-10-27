package com.finalysis.support;

import com.finalysis.vault.PlainDatabaseConnection;
import com.finalysis.vault.VaultStatus;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The rule for anything that writes chat or categorization output to disk (docs/plans/phase-8.md §8.2): only from the
 * test's own Testcontainers database, filled with synthetic sets, with the vault off. The eval reports in
 * {@code docs/eval/} are the only such output today; any future export follows the same rule. Called right before a
 * report is written.
 */
public final class EvalDatabaseGuard {

    private EvalDatabaseGuard() {
    }

    public static void check(PlainDatabaseConnection plain, VaultStatus vault, PostgreSQLContainer container) {
        if (!"PLAIN".equals(vault.storage())) {
            throw new IllegalStateException("Refusing to write eval output: the vault is on, so the data may be real");
        }
        if (container == null || !container.isRunning() || !container.getJdbcUrl().equals(plain.url())) {
            throw new IllegalStateException("Refusing to write eval output: the database is not this test's own "
                    + "Testcontainers database");
        }
    }
}
