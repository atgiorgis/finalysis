package com.finalysis.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The single Postgres that integration tests run against. Use it through {@link IntegrationTest}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Same image as compose.yaml, so tests run against the extension set we ship with.
    // Static so every Spring context in the JVM shares one container instead of starting its own.
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    // destroyMethod = "": one context closing must not stop the container other contexts still use.
    // Testcontainers' Ryuk removes it when the JVM exits.
    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }
}
