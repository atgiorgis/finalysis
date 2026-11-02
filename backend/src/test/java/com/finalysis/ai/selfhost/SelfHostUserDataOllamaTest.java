package com.finalysis.ai.selfhost;

import static com.finalysis.ai.selfhost.SelfHostFiles.executable;
import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.support.OllamaStub;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first boot's Ollama section (docs/plans/phase-9.md, 9c-6 notes), cut from the template's user data between its
 * markers and run under macOS's {@code /bin/bash} as cloud-init runs it: no HOME in the environment, {@code set -u}.
 * Ollama is the stub server on loopback; {@code systemctl} is a fake that logs its calls; {@code ollama} is a fake that
 * fails like the CLI did on the owner's first run, so any CLI call left in the section fails the test.
 */
class SelfHostUserDataOllamaTest {

    private static final String SECTION_START = "# --- finalysis: ollama section";
    private static final String SECTION_END = "# --- finalysis: end of the ollama section ---";
    private static final String CHAT_MODEL = "ministral-3:14b";

    @TempDir
    Path temp;

    private OllamaStub stub;
    private Path bin;

    @BeforeEach
    void fakes() throws IOException {
        stub = OllamaStub.start();
        bin = temp.resolve("bin");
        executable(bin.resolve("systemctl"), """
                #!/bin/bash
                printf '%s\\n' "$*" >> "$FAKE_DIR/systemctl.log"
                if [ "$1" = cat ] && [ -f "$FAKE_DIR/no-unit" ]; then
                    echo "No files found for ollama.service." >&2; exit 1
                fi
                exit 0
                """);
        executable(bin.resolve("ollama"), """
                #!/bin/bash
                printf '%s\\n' "$*" >> "$FAKE_DIR/ollama.log"
                echo 'panic: $HOME is not defined' >&2
                exit 2
                """);
        executable(bin.resolve("sleep"), "#!/bin/bash\nexit 0\n");
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    /** The section exactly as the template has it, after stand-ins for what the script defines above it. */
    private SelfHostFiles.Run runSection() throws Exception {
        String body = SelfHostFiles.userData().get(1);
        int start = body.indexOf(SECTION_START);
        int end = body.indexOf(SECTION_END);
        assertThat(start).as("the section's start marker").isNotNegative();
        assertThat(end).as("its end marker").isGreaterThan(start);
        String section = body.substring(start, end + SECTION_END.length());
        Path script = temp.resolve("ollama-section.sh");
        Files.writeString(script, """
                #!/bin/bash
                set -euo pipefail
                set +x
                fail() { echo "FAIL: $1"; exit 1; }
                CHAT_MODEL='%s'
                EMBED_MODEL=nomic-embed-text
                OLLAMA_LOCAL=http://127.0.0.1:%d
                OLLAMA_WAIT_TRIES=3
                """.formatted(CHAT_MODEL, stub.port()) + section + "\necho 'SECTION DONE'\n");
        return SelfHostFiles.bashBareEnvironment(script, bin + ":/usr/bin:/bin", Map.of("FAKE_DIR", temp.toString()));
    }

    private List<String> log(String name) throws IOException {
        Path file = temp.resolve(name);
        return Files.exists(file) ? Files.readAllLines(file) : List.of();
    }

    @Test
    void withNoHomeTheServiceIsEnabledWaitedForAndBothModelsArePulledThroughTheApi() throws Exception {
        SelfHostFiles.Run run = runSection();

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.output()).contains("SECTION DONE").doesNotContain("FAIL");
        assertThat(log("systemctl.log")).containsExactly("daemon-reload", "cat ollama.service",
                "enable --now --no-block ollama");
        assertThat(log("ollama.log")).as("no ollama CLI call").isEmpty();
        List<OllamaStub.Request> requests = stub.requests();
        assertThat(requests).extracting(OllamaStub.Request::method, OllamaStub.Request::path).containsExactly(
                org.assertj.core.groups.Tuple.tuple("GET", "/api/version"),
                org.assertj.core.groups.Tuple.tuple("POST", "/api/pull"),
                org.assertj.core.groups.Tuple.tuple("POST", "/api/pull"));
        assertThat(requests.get(1).json().get("name").stringValue()).isEqualTo(CHAT_MODEL);
        assertThat(requests.get(1).json().get("stream").booleanValue()).isFalse();
        assertThat(OllamaStub.fields(requests.get(1).json())).containsExactly("name", "stream");
        assertThat(requests.get(2).json().get("name").stringValue()).isEqualTo("nomic-embed-text");
        assertThat(requests.get(2).json().get("stream").booleanValue()).isFalse();
    }

    @Test
    void anOllamaThatNeverAnswersFailsWithAClearReasonAndPullsNothing() throws Exception {
        stub.stopServing();

        SelfHostFiles.Run run = runSection();

        assertThat(run.exit()).isNotZero();
        assertThat(run.output()).contains("FAIL: Ollama didn't answer on http://127.0.0.1:" + stub.port()
                + "/api/version after 3 tries").doesNotContain("SECTION DONE");
        assertThat(stub.requests()).noneMatch(r -> r.path().equals("/api/pull"));
    }

    @Test
    void aMissingUnitFailsBeforeAnythingIsStarted() throws Exception {
        Files.writeString(temp.resolve("no-unit"), "");

        SelfHostFiles.Run run = runSection();

        assertThat(run.exit()).isNotZero();
        assertThat(run.output()).contains("FAIL: Ollama's installer left no ollama.service unit");
        assertThat(log("systemctl.log")).containsExactly("daemon-reload", "cat ollama.service");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void aFailedPullFailsTheBootNamingTheModel() throws Exception {
        stub.pullReply(OllamaStub.status(500, "pull model manifest: file does not exist"));

        SelfHostFiles.Run run = runSection();

        assertThat(run.exit()).isNotZero();
        assertThat(run.output()).contains("FAIL: Pulling " + CHAT_MODEL + " through Ollama's API failed");
        assertThat(stub.requests("/api/pull")).hasSize(1);
    }

    @Test
    void aPullThatDoesntReportSuccessFailsTheBoot() throws Exception {
        stub.pullReply(OllamaStub.raw("{\"status\":\"pulling manifest\"}"));

        SelfHostFiles.Run run = runSection();

        assertThat(run.exit()).isNotZero();
        assertThat(run.output()).contains("FAIL: Pulling " + CHAT_MODEL + ": Ollama didn't report success");
    }
}
