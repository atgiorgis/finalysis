package com.finalysis.vault;

/**
 * The result of {@code db-check.sh} (docs/plans/phase-8.md §4.4): OK, or a reason with the installed and required
 * versions or the item that conflicts (a file name, a settings domain or a port; never a path).
 */
public record ReadyCheck(boolean ok, String reason, String installed, String required, String item) {

    static final ReadyCheck OK = new ReadyCheck(true, null, null, null, null);

    /** Parses the script's one line: {@code OK} or {@code NOT_READY <reason> [key=value]...}. */
    static ReadyCheck parse(String line) {
        String text = line == null ? "" : line.strip();
        if (text.equals("OK")) {
            return OK;
        }
        String[] parts = text.split("\\s+");
        if (parts.length < 2 || !parts[0].equals("NOT_READY") || !parts[1].matches("[a-z-]{1,40}")) {
            return new ReadyCheck(false, "check-failed", null, null, null);
        }
        String installed = null;
        String required = null;
        String item = null;
        for (int i = 2; i < parts.length; i++) {
            String[] pair = parts[i].split("=", 2);
            if (pair.length != 2 || !pair[1].matches("[A-Za-z0-9._@-]{1,100}")) {
                continue;
            }
            switch (pair[0]) {
                case "installed" -> installed = pair[1];
                case "required" -> required = pair[1];
                case "item" -> item = pair[1];
                default -> { }
            }
        }
        return new ReadyCheck(false, parts[1], installed, required, item);
    }

    /** One plain sentence for the unlock screen. */
    public String message() {
        if (ok) {
            return "Ready.";
        }
        return switch (reason) {
            case "postgres-missing" -> "Finalysis needs PostgreSQL " + or(required, "17") + " from Postgres.app, and "
                    + "none was found. Install Postgres.app with PostgreSQL 17 (postgresapp.com), or set "
                    + "finalysis.vault.pg-bin.";
            case "postgres-wrong-version" -> "Finalysis needs PostgreSQL " + or(required, "17") + "; the installed one "
                    + "is PostgreSQL " + or(installed, "of another version")
                    + ". Install Postgres.app's PostgreSQL 17.";
            case "pgvector-missing" -> "Finalysis needs pgvector " + or(required, "")
                    + ", and this PostgreSQL has none "
                    + "installed. Use Postgres.app's PostgreSQL 17, which includes it.";
            case "pgvector-wrong-version" -> "Finalysis needs pgvector " + or(required, "") + "; the installed one is "
                    + or(installed, "another version") + ". See the README's setup section.";
            case "launchd-conflict" -> "A background service is set to start a database from the Finalysis vault. "
                    + "Remove it (see the README) and restart Finalysis.";
            case "postgresapp-conflict" -> "Postgres.app has a server that uses the Finalysis vault. Remove that "
                    + "server in Postgres.app (keep its data) and restart Finalysis.";
            case "port-in-use" -> "Port " + or(item, "of the vault's database") + " is already in use. Stop what uses "
                    + "it, or set finalysis.vault.db-port.";
            default -> "The vault's database check did not pass. See the README's setup section.";
        };
    }

    private static String or(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
