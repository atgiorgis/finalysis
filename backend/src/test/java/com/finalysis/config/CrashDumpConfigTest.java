package com.finalysis.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * No heap dumps of the running app (docs/plans/phase-8.md §8.2): a heap dump of an unlocked backend would hold
 * decrypted data and the database password, written outside the vault. Nothing that starts the app may ask for one:
 * the Maven build (and its spring-boot:run arguments), the wrapper's JVM settings, the application config, the README.
 */
class CrashDumpConfigTest {

    private static final List<Path> STARTERS = List.of(
            Path.of("pom.xml"),
            Path.of(".mvn", "jvm.config"),
            Path.of(".mvn", "maven.config"),
            Path.of("src", "main", "resources", "application.yml"),
            Path.of("src", "main", "resources", "application-aws.yml"),
            Path.of("..", "README.md"));

    @Test
    void nothingStartsTheAppWithHeapDumps() throws IOException {
        for (Path file : STARTERS) {
            if (!Files.exists(file)) {
                continue;
            }
            String text = Files.readString(file);
            assertThat(text).as(file.toString()).doesNotContain("HeapDumpOnOutOfMemoryError")
                    .doesNotContain("HeapDumpPath").doesNotContain("-XX:+CreateCoredumpOnCrash");
        }
        assertThat(Path.of("pom.xml")).exists();
    }

    @Test
    void theReadmeSaysWhyThereAreNone() throws IOException {
        assertThat(Files.readString(Path.of("..", "README.md"))).contains("No heap or core dumps");
    }
}
