package com.finalysis.ai.selfhost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * cfn-lint on the self-hosted server's templates (docs/plans/phase-9.md §5.6): no error and no warning. Skipped through
 * a JUnit assumption, so Maven reports it as skipped, when cfn-lint isn't on the PATH (README, "Self-hosted": {@code pip3
 * install --user cfn-lint}, then put its bin folder on the PATH).
 */
class CfnLintTest {

    @Test
    void bothTemplatesPassCfnLint() throws Exception {
        Optional<Path> cfnLint = onPath("cfn-lint");
        assumeTrue(cfnLint.isPresent(), "cfn-lint isn't installed (README, Self-hosted)");

        Process process = new ProcessBuilder(cfnLint.get().toString(), SelfHostFiles.TEMPLATE.toString(),
                SelfHostFiles.BILLING_TEMPLATE.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
        // cfn-lint's exit code is a bit mask: 2 errors, 4 warnings, 8 informational notes.
        assertThat(process.exitValue() & 6).as(output).isZero();
    }

    private static Optional<Path> onPath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        return Arrays.stream(path.split(File.pathSeparator)).map(dir -> Path.of(dir, tool))
                .filter(Files::isExecutable).findFirst();
    }
}
