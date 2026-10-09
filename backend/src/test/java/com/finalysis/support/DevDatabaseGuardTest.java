package com.finalysis.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DevDatabaseGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://localhost:5432/finalysis",
            "jdbc:postgresql://127.0.0.1:5432/finalysis",
            "jdbc:postgresql://[::1]:5432/finalysis",
            "jdbc:postgresql://localhost:5432/finalysis?sslmode=disable",
    })
    void rejectsDevDatabase(String url) {
        assertThatThrownBy(() -> DevDatabaseGuard.check(url))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@IntegrationTest");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://localhost:52118/test?loggerLevel=OFF",
            "jdbc:postgresql://localhost:5432/finalysis_other",
    })
    void allowsOtherDatabases(String url) {
        assertThatCode(() -> DevDatabaseGuard.check(url)).doesNotThrowAnyException();
    }
}
