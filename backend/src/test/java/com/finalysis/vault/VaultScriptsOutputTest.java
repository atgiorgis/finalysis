package com.finalysis.vault;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * {@link VaultScripts} (docs/plans/phase-8.md §8.2): a script's output never reaches the log, whatever it prints and
 * however it ends; only its name and exit code do. The secret given on stdin is zeroed once written. No disk image:
 * stand-in scripts in a temporary folder.
 */
@ExtendWith(OutputCaptureExtension.class)
class VaultScriptsOutputTest {

    @TempDir
    Path scripts;

    @Test
    void outputAndSecretsStayOutOfTheLog(CapturedOutput output) throws IOException {
        // Echoes what it reads on stdin, prints a marker on both streams, and fails.
        script("echo.sh", "read -r secret; echo \"OUTMARKER $secret\"; echo \"ERRMARKER\" >&2; exit 7");
        byte[] secret = "SECRETMARKER-key".getBytes(UTF_8);

        VaultScripts.Result result = new VaultScripts(scripts).run("echo.sh", secret, "ARGMARKER");

        assertThat(result.exit()).isEqualTo(7);
        assertThat(result.out()).contains("OUTMARKER SECRETMARKER-key", "ERRMARKER");
        assertThat(secret).containsOnly(0);
        assertThat(output.getAll()).contains("vault script echo.sh exited 7")
                .doesNotContain("OUTMARKER").doesNotContain("ERRMARKER").doesNotContain("SECRETMARKER")
                .doesNotContain("ARGMARKER");
    }

    @Test
    void aMissingScriptIsNamedAndNothingRuns(CapturedOutput output) {
        byte[] secret = "SECRETMARKER-2".getBytes(UTF_8);

        VaultScripts.Result result = new VaultScripts(scripts).run("absent.sh", secret);

        assertThat(result.exit()).isEqualTo(-1);
        assertThat(secret).containsOnly(0);
        assertThat(output.getAll()).contains("vault script absent.sh is missing").doesNotContain("SECRETMARKER")
                .doesNotContain(scripts.toString());
    }

    private void script(String name, String body) throws IOException {
        Path path = scripts.resolve(name);
        Files.writeString(path, "#!/bin/bash\n" + body + "\n");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }
}
