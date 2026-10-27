package com.finalysis.vault;

import com.zaxxer.hikari.HikariConfig;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;

/**
 * Opens and closes the database behind {@link VaultDataSource} (docs/plans/phase-8.md §5.1): the pool, then Flyway, then
 * Hibernate's check that every entity matches the schema (what {@code ddl-auto: validate} did at startup), then the
 * collation-version check. Closing aborts every connection.
 */
class VaultDatabase {

    /** The application_name of the pool's connections, so a test can count them in {@code pg_stat_activity}. */
    static final String APPLICATION_NAME = "finalysis";

    /** The database's recorded ICU collation version differs from the library's (Postgres.app updated ICU). */
    record CollationChange(String recorded, String actual) {
    }

    private final VaultDataSource dataSource;
    private final HikariConfig poolSettings;
    private final Flyway flyway;
    private final EntityManagerFactory entityManagerFactory;

    /**
     * @param bootFlyway Boot's Flyway, for its configuration only: it may be bound to a service connection of its own
     *                   (Testcontainers), so the migration always runs on {@link VaultDataSource}
     */
    VaultDatabase(VaultDataSource dataSource, HikariConfig poolSettings, Flyway bootFlyway,
                  EntityManagerFactory entityManagerFactory) {
        this.dataSource = dataSource;
        this.poolSettings = poolSettings;
        this.flyway = Flyway.configure().configuration(bootFlyway.getConfiguration()).dataSource(dataSource).load();
        this.entityManagerFactory = entityManagerFactory;
    }

    /** Opens the pool, migrates and checks the schema; on any failure the pool is closed again and it rethrows. */
    void open(String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        poolSettings.copyStateTo(config);
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setPoolName("vault");
        config.addDataSourceProperty("ApplicationName", APPLICATION_NAME);
        config.setInitializationFailTimeout(-1);
        try {
            dataSource.open(config);
            flyway.migrate();
            entityManagerFactory.unwrap(SessionFactory.class).getSchemaManager().validateMappedObjects();
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
    }

    void close() {
        dataSource.close();
    }

    /**
     * Compares the database's recorded collation version with the library's (PostgreSQL 15+): different after an ICU
     * update, when text indexes may sort differently and need {@code REINDEX DATABASE} then
     * {@code ALTER DATABASE ... REFRESH COLLATION VERSION}. Null when they match or a version is unknown.
     */
    CollationChange collationChange() {
        String sql = "SELECT datcollversion, pg_database_collation_actual_version(oid) FROM pg_database "
                + "WHERE datname = current_database()";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return null;
            }
            String recorded = rows.getString(1);
            String actual = rows.getString(2);
            if (recorded == null || actual == null || recorded.equals(actual)) {
                return null;
            }
            return new CollationChange(recorded, actual);
        } catch (SQLException e) {
            return null;
        }
    }

    /** {@code vault.idle-lock-minutes} from app_setting, or the default when unset or out of range. */
    int idleLockMinutes(int fallback) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT value FROM app_setting WHERE key = 'vault.idle-lock-minutes'");
             ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                int minutes = Integer.parseInt(rows.getString(1).strip());
                if (minutes >= 1 && minutes <= 240) {
                    return minutes;
                }
            }
            return fallback;
        } catch (SQLException | NumberFormatException e) {
            return fallback;
        }
    }
}
