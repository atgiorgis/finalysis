package com.finalysis.vault;

/**
 * The vault's Postgres cluster (docs/plans/phase-8.md §4.4): {@link NativeDatabaseRuntime} on Postgres.app; in tests,
 * one that points at a Testcontainers database.
 */
public interface DatabaseRuntime {

    enum Stopped { FAST, IMMEDIATE }

    /** Where the open cluster is; the password is only ever held in memory. */
    record Connection(String url, String user, String password) {
        @Override
        public String toString() {
            return "Connection[" + url + "]";
        }
    }

    /** The startup check: versions, conflicts, port. Run while locked. */
    ReadyCheck check();

    /** Creates the cluster in the mounted vault, with this superuser password. */
    void init(char[] password);

    /** Starts the cluster in the mounted vault. */
    Connection start();

    /** Stops the cluster, if it runs. */
    Stopped stop();

    final class DatabaseException extends RuntimeException {
        DatabaseException(String what) {
            super(what, null, false, false);
        }
    }
}
