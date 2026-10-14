package com.finalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.support.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class DatabaseConnectionTest {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    DatabaseConnectionTest(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @Test
    void connectsToPostgresWithPgvectorAvailable() {
        Integer vectorExtensions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_available_extensions WHERE name = 'vector'", Integer.class);

        assertThat(vectorExtensions).isEqualTo(1);
    }

    /** src/test/resources/application.properties: small pools, so every cached context fits in Postgres. */
    @Test
    void testPoolsAreSmall() throws Exception {
        assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(3);
    }
}
