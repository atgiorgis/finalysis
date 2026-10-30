package com.finalysis.ai.selfhost;

import static com.finalysis.ai.selfhost.SelfHostFiles.WATCHDOG;
import static com.finalysis.ai.selfhost.SelfHostFiles.executable;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code infra/selfhost/idle-watchdog.sh} (docs/plans/phase-9.md §5.1, §5.6) against canned journal lines: fake
 * {@code journalctl}, {@code systemctl} and {@code logger} first on the PATH, a given clock and uptime. Status checks
 * alone stop the server after 20 minutes; a chat, generate or embed request resets the count; nothing stops in the
 * first 20 minutes after boot. The template carries this same file ({@link SelfHostTemplateTest}).
 */
class IdleWatchdogScriptTest {

    /** Boot time, in Unix seconds. */
    private static final long BOOT = 1_800_000_000L;

    @TempDir
    Path temp;

    private Path bin;
    private Path journal;
    private Path poweroffs;
    private Path journalArguments;
    private Path logged;
    private final List<String> lines = new ArrayList<>();

    @BeforeEach
    void fakes() throws IOException {
        bin = temp.resolve("bin");
        journal = temp.resolve("journal.txt");
        poweroffs = temp.resolve("systemctl.log");
        journalArguments = temp.resolve("journalctl.log");
        logged = temp.resolve("logger.log");
        Files.writeString(journal, "");
        executable(bin.resolve("journalctl"), """
                #!/bin/bash
                printf '%s\\n' "$*" >> "$FAKE_DIR/journalctl.log"
                [ -f "$FAKE_DIR/journal-broken" ] && exit 1
                /bin/cat "$FAKE_DIR/journal.txt"
                """);
        executable(bin.resolve("systemctl"), "#!/bin/bash\nprintf '%s\\n' \"$*\" >> \"$FAKE_DIR/systemctl.log\"\n");
        executable(bin.resolve("logger"), "#!/bin/bash\nprintf '%s\\n' \"$*\" >> \"$FAKE_DIR/logger.log\"\n");
    }

    /** One of Ollama's request lines at this many seconds after boot, as journalctl's short-unix output shows it. */
    private void request(long secondsAfterBoot, String method, String path) {
        lines.add((BOOT + secondsAfterBoot) + ".412345 ip-172-31-5-9 ollama[812]: [GIN] 2027/01/15 - 08:00:00 | 200 |"
                + "  1.204513s |  100.101.102.103 | " + String.format("%-8s", method) + "\"" + path + "\"");
    }

    /** A line that isn't a request (Ollama's own logging). */
    private void otherLine(long secondsAfterBoot, String text) {
        lines.add((BOOT + secondsAfterBoot) + ".000001 ip-172-31-5-9 ollama[812]: " + text);
    }

    /** The app's Test button and the status checks, every 5 seconds over this span. */
    private void statusChecks(long fromSecond, long toSecond) {
        for (long s = fromSecond; s < toSecond; s += 5) {
            request(s, "GET", s % 15 == 0 ? "/api/version" : s % 10 == 0 ? "/api/tags" : "/api/ps");
        }
    }

    /** Runs the watchdog at this many seconds after boot; true when it powered off. */
    private boolean runAt(long secondsAfterBoot) throws Exception {
        long now = BOOT + secondsAfterBoot;
        Files.write(journal, lines.stream()
                .filter(line -> Long.parseLong(line.substring(0, line.indexOf('.'))) <= now).toList());
        Files.writeString(temp.resolve("uptime"), secondsAfterBoot + ".37 1234.56\n");
        Files.deleteIfExists(poweroffs);
        SelfHostFiles.Run run = SelfHostFiles.bash(WATCHDOG, bin + ":/usr/bin:/bin",
                Map.of("FAKE_DIR", temp.toString(), "WATCHDOG_NOW", String.valueOf(BOOT + secondsAfterBoot),
                        "WATCHDOG_UPTIME_FILE", temp.resolve("uptime").toString()), "");
        assertThat(run.exit()).as(run.output()).isZero();
        if (!Files.exists(poweroffs)) {
            return false;
        }
        assertThat(Files.readString(poweroffs)).isEqualTo("poweroff\n");
        return true;
    }

    @Test
    void nothingStopsInTheFirst20MinutesAfterBootEvenWithNoRequestAtAll() throws Exception {
        assertThat(runAt(60)).isFalse();
        assertThat(runAt(1199)).isFalse();
        assertThat(Files.exists(journalArguments)).as("the journal isn't even read before 20 minutes").isFalse();
        assertThat(runAt(1200)).isTrue();
    }

    @Test
    void statusChecksAloneStopTheServerAfter20Minutes() throws Exception {
        statusChecks(0, 3600);
        otherLine(30, "time=2027-01-15T08:00:30Z level=INFO msg=\"llama runner started\"");

        assertThat(runAt(1199)).isFalse();
        assertThat(runAt(1200)).isTrue();
        assertThat(Files.readString(logged)).contains("No model request for 20 minutes; powering off");
    }

    @Test
    void aChatRequestResetsTheCount() throws Exception {
        statusChecks(0, 7200);
        request(1500, "POST", "/api/chat");

        assertThat(runAt(1500)).isFalse();
        assertThat(runAt(2699)).as("19 minutes 59 seconds after the request").isFalse();
        assertThat(runAt(2701)).as("20 minutes after the request").isTrue();
    }

    @Test
    void generateAndEmbedCountAsModelRequestsToo() throws Exception {
        request(3000, "POST", "/api/generate");
        assertThat(runAt(3600)).isFalse();

        lines.clear();
        request(3000, "POST", "/api/embed");
        assertThat(runAt(3600)).isFalse();
    }

    @Test
    void onlyPostsToTheThreeModelEndpointsCount() throws Exception {
        request(3500, "GET", "/api/version");
        request(3500, "GET", "/api/tags");
        request(3500, "GET", "/api/ps");
        request(3500, "POST", "/api/show");
        request(3500, "GET", "/api/chat");
        request(3500, "POST", "/api/pull");
        request(3500, "HEAD", "/");
        otherLine(3500, "POST \"/api/chat\" mentioned in a line that isn't a request log");

        assertThat(runAt(3600)).isTrue();
    }

    @Test
    void aRequestOlderThan20MinutesDoesntCountEvenIfTheJournalReturnsIt() throws Exception {
        request(2000, "POST", "/api/chat");

        assertThat(runAt(3201)).isTrue();
    }

    @Test
    void itAsksTheJournalForOllamasLast20MinutesInJournaldsOwnTimes() throws Exception {
        runAt(3600);

        assertThat(Files.readString(journalArguments).strip()).isEqualTo(
                "--unit=ollama --since=@" + (BOOT + 3600 - 1200) + " --output=short-unix --no-pager --quiet");
    }

    @Test
    void aJournalThatCantBeReadNeverPowersOff() throws Exception {
        Files.writeString(temp.resolve("journal-broken"), "");
        Files.writeString(temp.resolve("uptime"), "3600.00 1.00\n");

        SelfHostFiles.Run run = SelfHostFiles.bash(WATCHDOG, bin + ":/usr/bin:/bin",
                Map.of("FAKE_DIR", temp.toString(), "WATCHDOG_NOW", String.valueOf(BOOT + 3600),
                        "WATCHDOG_UPTIME_FILE", temp.resolve("uptime").toString()), "");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(poweroffs).doesNotExist();
        assertThat(Files.readString(logged)).contains("Could not read Ollama's journal; not powering off.");
    }

    @Test
    void aWholeSessionStatusChecksThenChatsThenIdle() throws Exception {
        // Booted, the app's status checks every 5 s throughout, the warm-up, chats, then nothing but status checks.
        statusChecks(0, 6000);
        request(600, "POST", "/api/generate");
        request(1300, "POST", "/api/chat");
        request(1900, "POST", "/api/chat");
        request(1901, "POST", "/api/embed");

        List<Long> stoppedAt = new ArrayList<>();
        for (long minute = 1; minute <= 100; minute++) {
            if (runAt(minute * 60)) {
                stoppedAt.add(minute);
            }
        }

        // The last request is at 1901 s; 1901 + 1200 = 3101 s, so the first minute tick at or after it is 52 (3120 s).
        assertThat(stoppedAt).first().isEqualTo(52L);
    }
}
