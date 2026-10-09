package com.finalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class DatabaseConnectionTest {

    private final JdbcTemplate jdbcTemplate;

    DatabaseConnectionTest(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void connectsToPostgresWithPgvectorAvailable() {
        Integer vectorExtensions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_available_extensions WHERE name = 'vector'", Integer.class);

        assertThat(vectorExtensions).isEqualTo(1);
    }
}
