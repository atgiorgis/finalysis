package com.finalysis.vault;

/**
 * The plain database the app opens at startup with the vault off (docs/plans/phase-8.md §5.5): Testcontainers' in the
 * tests, {@code spring.datasource} (compose.yaml) in development. Public so the tests' guard against the dev database
 * can read the URL. The password is never printed.
 */
public record PlainDatabaseConnection(String url, String user, String password) {

    @Override
    public String toString() {
        return "PlainDatabaseConnection[" + url + "]";
    }
}
