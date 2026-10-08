package com.finalysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DatabaseConnectionTest {

    // Same image as compose.yaml, so tests run against the extension set we ship with.
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

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
