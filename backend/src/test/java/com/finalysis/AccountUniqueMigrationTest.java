package com.finalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V12's unique account index against a scratch database migrated to V11: existing duplicates stop
 * the migration with a message naming their ids and nothing is deleted; distinct accounts migrate.
 * The shared test database is never touched.
 */
@IntegrationTest
class AccountUniqueMigrationTest {

    private static final String DATABASE = "finalysis_v12_unique";

    private final JdbcTemplate shared;
    private final PostgreSQLContainer postgres;
    private DriverManagerDataSource scratch;

    AccountUniqueMigrationTest(JdbcTemplate shared, PostgreSQLContainer postgres) {
        this.shared = shared;
        this.postgres = postgres;
    }

    @BeforeEach
    void createScratchDatabase() {
        shared.execute("DROP DATABASE IF EXISTS " + DATABASE);
        shared.execute("CREATE DATABASE " + DATABASE);
        String url = postgres.getJdbcUrl().replaceFirst("/" + postgres.getDatabaseName() + "(?=\\?|$)", "/" + DATABASE);
        scratch = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
    }

    @AfterEach
    void dropScratchDatabase() {
        shared.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
    }

    @Test
    void duplicatesStopTheMigrationWithTheirIds() {
        migrateTo("11");
        JdbcTemplate jdbc = new JdbcTemplate(scratch);
        long first = account(jdbc, "Northfield Bank", "CHECKING", "1001");
        long second = account(jdbc, " northfield BANK ", "CHECKING", "1001");
        account(jdbc, "Northfield Bank", "SAVINGS", "1001");
        long card = account(jdbc, "Summit Card Services", "CREDIT_CARD", "0042");
        long cardAgain = account(jdbc, "summit card services", "CREDIT_CARD", "0042");

        assertThatThrownBy(() -> migrateTo("12"))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V12: duplicate accounts (same institution, type and last four), account ids: "
                        + first + ", " + second + "; " + card + ", " + cardAgain)
                .hasMessageContaining("scripts/reset-dev-db.sh")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContainIgnoringCase("northfield")
                        .doesNotContain("1001"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM account", Integer.class)).isEqualTo(5);
        assertThat(index(jdbc)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class)).isEqualTo("11");
    }

    @Test
    void distinctAccountsMigrate() {
        migrateTo("11");
        JdbcTemplate jdbc = new JdbcTemplate(scratch);
        account(jdbc, "Northfield Bank", "CHECKING", "1001");
        account(jdbc, "Northfield Bank", "SAVINGS", "1001");
        account(jdbc, "Northfield Bank", "CHECKING", "1002");
        account(jdbc, "Summit Card Services", "CHECKING", "1001");

        migrateTo("12");

        assertThat(index(jdbc)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account", Integer.class)).isEqualTo(4);
    }

    private void migrateTo(String version) {
        Flyway.configure().dataSource(scratch).target(version).load().migrate();
    }

    private static long account(JdbcTemplate jdbc, String institution, String type, String lastFour) {
        return jdbc.queryForObject("""
                INSERT INTO account (name, institution, type, last_four)
                VALUES ('Test', ?, ?, ?) RETURNING id""", Long.class, institution, type, lastFour);
    }

    private static int index(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_account_identity'",
                Integer.class);
    }
}
