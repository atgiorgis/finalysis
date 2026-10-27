package com.finalysis.vault;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * The app's only {@link DataSource} (docs/plans/phase-8.md §5.1): refuses every connection while closed (locked), and
 * delegates to a Hikari pool opened on unlock and closed on lock. Nothing connects at startup. The second line behind
 * {@link VaultGate}.
 */
public final class VaultDataSource implements DataSource {

    private volatile HikariDataSource pool;

    /** The SQL state of a refused connection: "connection does not exist". */
    static final String LOCKED_SQL_STATE = "08003";

    void open(HikariConfig config) {
        HikariDataSource opened = new HikariDataSource(config);
        HikariDataSource previous = pool;
        pool = opened;
        if (previous != null) {
            previous.close();
        }
    }

    /** Closes every connection; active ones are aborted. */
    void close() {
        HikariDataSource closing = pool;
        pool = null;
        if (closing != null) {
            closing.close();
        }
    }

    boolean isOpen() {
        return pool != null;
    }

    /** The open pool's JDBC URL, or null; for the tests' guard against the dev database. */
    public String jdbcUrl() {
        HikariDataSource current = pool;
        return current == null ? null : current.getJdbcUrl();
    }

    private HikariDataSource pool() throws SQLException {
        HikariDataSource current = pool;
        if (current == null) {
            throw new SQLException("The vault is locked", LOCKED_SQL_STATE);
        }
        return current;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return pool().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("connections come from the vault's pool");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) {
            return (T) this;
        }
        HikariDataSource current = pool();
        if (type.isInstance(current)) {
            return (T) current;
        }
        return current.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        if (type.isInstance(this)) {
            return true;
        }
        HikariDataSource current = pool;
        return current != null && (type.isInstance(current) || current.isWrapperFor(type));
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // no log writer
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // the pool's connection timeout applies
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
