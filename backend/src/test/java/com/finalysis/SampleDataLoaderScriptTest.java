package com.finalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.finalysis.support.ImportTestSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code scripts/load-sample-data.sh} (the sample-data loader, the seed of QA-06) against a fake backend: the JDK's
 * {@link HttpServer} on a random loopback port, as {@code OllamaStub} is. The real script runs under bash with logging
 * wrappers for {@code curl} and {@code plutil} first on the PATH, so every argument either was given is recorded:
 * the calls in order, the unlock with the password from stdin and never in an argument or the output, the refusal of a
 * vault that has accounts, the suggested mapping saved once per account, the per-file report and the opt-in
 * categorization. macOS only (plutil), like the app.
 */
class SampleDataLoaderScriptTest {

    private static final Path REPO = ImportTestSupport.SAMPLE_DATA.toAbsolutePath().normalize().getParent();
    private static final Path SCRIPT = REPO.resolve("scripts/load-sample-data.sh");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** With a quote, a backslash and a tab, so the JSON escaping is exercised. */
    private static final String PASSWORD = "correct horse \"battery\" \\ staple\tsample";
    private static final String MAPPING = """
            {"delimiter":",","encoding":"UTF-8","headerRow":1,"skipRows":0,"dateColumn":"Date","dateFormat":"yyyy-MM-dd",\
            "descriptionColumns":["Description"],"amountColumn":"Amount","debitColumn":null,"creditColumn":null,\
            "sign":"NEGATIVE_IS_DEBIT"}""";

    /** One request the fake received. */
    record Call(String method, String target, String body) {

        String line() {
            return method + " " + target;
        }
    }

    private HttpServer server;
    private final List<Call> calls = new ArrayList<>();
    private boolean locked;
    private boolean hasAccounts;
    private int nextId = 11;

    @TempDir
    Path temp;

    @BeforeEach
    void start() throws IOException {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/plutil")) && Files.isExecutable(Path.of("/usr/bin/curl")),
                "macOS's plutil and curl");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------------------- the fake backend

    private synchronized void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
        String method = exchange.getRequestMethod();
        String target = exchange.getRequestURI().toString();
        String path = exchange.getRequestURI().getPath();
        calls.add(new Call(method, target, body));
        if (method.equals("GET") && path.equals("/api/status")) {
            send(exchange, 200, "{\"status\":\"ok\",\"vault\":{\"state\":\"" + (locked ? "LOCKED" : "UNLOCKED") + "\"}}");
        } else if (method.equals("POST") && path.equals("/api/vault/unlock")) {
            if (JSON.readTree(body).get("password").stringValue().equals(PASSWORD)) {
                locked = false;
                send(exchange, 204, "");
            } else {
                send(exchange, 401, "{\"type\":\"urn:finalysis:problem:wrong-password\",\"detail\":\"The password is wrong.\"}");
            }
        } else if (method.equals("GET") && path.equals("/api/accounts")) {
            send(exchange, 200, hasAccounts ? "[{\"id\":1,\"name\":\"Everyday Checking\"}]" : "[]");
        } else if (method.equals("POST") && path.equals("/api/accounts")) {
            send(exchange, 201, "{\"id\":" + nextId++ + "}");
        } else if (method.equals("POST") && path.endsWith("/imports/preview")) {
            send(exchange, 200, "{\"format\":\"csv\",\"suggestedMapping\":{\"mapping\":" + MAPPING + ",\"notes\":[]}}");
        } else if (method.equals("PUT") && path.endsWith("/csv-mapping")) {
            send(exchange, 201, "{}");
        } else if (method.equals("POST") && path.endsWith("/imports")) {
            String file = fileName(body);
            if (file.contains("savings_2026-05")) {
                send(exchange, 409, "{\"type\":\"urn:finalysis:problem:already-imported\",\"detail\":\"Already imported.\"}");
            } else if (body.contains("BROKEN-FIXTURE") || file.equals("summit-card_2026-06.csv") && seenSummitJune()) {
                send(exchange, 201, "{\"statementId\":9,\"status\":\"NEEDS_REVIEW\",\"inserted\":3,\"duplicates\":0,"
                        + "\"balanceCheck\":\"FAILED\"}");
            } else {
                send(exchange, 201, "{\"statementId\":9,\"status\":\"IMPORTED\",\"inserted\":12,\"duplicates\":0,"
                        + "\"balanceCheck\":\"PASSED\"}");
            }
        } else if (method.equals("POST") && path.equals("/api/categorization/run")) {
            send(exchange, 200, "{\"ai\":{\"status\":\"DONE\"},\"summary\":{\"categorized\":140,\"toReview\":3,"
                    + "\"heldBack\":2,\"modelCalls\":6,\"merchantsIndexed\":40}}");
        } else {
            send(exchange, 404, "{\"detail\":\"no such fake route\"}");
        }
    }

    /** The manifest lists the good June file of the Summit card, then the broken one of the same name. */
    private boolean seenSummitJune() {
        return calls.stream().filter(c -> c.method().equals("POST") && c.target().contains("/imports?"))
                .filter(c -> fileName(c.body()).equals("summit-card_2026-06.csv")).count() > 1;
    }

    private static String fileName(String multipart) {
        Matcher m = Pattern.compile("filename=\"([^\"]+)\"").matcher(multipart);
        return m.find() ? m.group(1) : "";
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    // ---------------------------------------------------------------------------- running the script

    /** What a run printed, its exit code, and every argument curl and plutil were given. */
    record Run(int exit, String output, String arguments) {
    }

    private Run run(String stdin, String... options) throws Exception {
        Path bin = Files.createDirectories(temp.resolve("bin"));
        Path log = temp.resolve("arguments.log");
        for (String tool : List.of("curl", "plutil")) {
            Path wrapper = bin.resolve(tool);
            Files.writeString(wrapper, "#!/bin/bash\nprintf '%s\\n' \"$@\" >> \"$ARGUMENT_LOG\"\nexec /usr/bin/" + tool
                    + " \"$@\"\n");
            wrapper.toFile().setExecutable(true);
        }
        List<String> command = new ArrayList<>(List.of("/bin/bash", SCRIPT.toString(), "--port",
                String.valueOf(server.getAddress().getPort())));
        command.addAll(List.of(options));
        ProcessBuilder builder = new ProcessBuilder(command).directory(REPO.toFile()).redirectErrorStream(true);
        builder.environment().putAll(Map.of("PATH", bin + ":/usr/bin:/bin", "ARGUMENT_LOG", log.toString()));
        Process process = builder.start();
        try (OutputStream in = process.getOutputStream()) {
            in.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the script finishes").isTrue();
        String arguments = Files.exists(log) ? Files.readString(log) : "";
        return new Run(process.exitValue(), output, arguments);
    }

    /** The manifest's rows: file, account slug, period, balances. */
    private static List<String[]> manifest() throws IOException {
        return Files.readAllLines(REPO.resolve("sample-data/statements-manifest.csv")).stream().skip(1)
                .filter(line -> !line.isBlank()).map(line -> line.split(",", -1)).toList();
    }

    private List<String> lines() {
        return calls.stream().map(Call::line).toList();
    }

    // ---------------------------------------------------------------------------- the tests

    @Test
    void aLockedVaultIsUnlockedThenTheAccountsAndEveryFileGoInManifestOrder() throws Exception {
        locked = true;

        Run run = run(PASSWORD + "\n");

        assertThat(run.exit()).as(run.output()).isZero();
        List<String> expected = new ArrayList<>(List.of("GET /api/status", "POST /api/vault/unlock", "GET /api/accounts",
                "POST /api/accounts", "POST /api/accounts", "POST /api/accounts", "POST /api/accounts"));
        Map<String, Integer> ids = Map.of("northfield-checking", 11, "northfield-savings", 12, "apex-visa", 13,
                "summit-card", 14);
        List<String> mapped = new ArrayList<>();
        for (String[] row : manifest()) {
            int id = ids.get(row[1]);
            if (!mapped.contains(row[1])) {
                mapped.add(row[1]);
                expected.add("POST /api/accounts/" + id + "/imports/preview");
                expected.add("PUT /api/accounts/" + id + "/csv-mapping");
            }
            String query = "periodStart=" + row[2] + "&periodEnd=" + row[3]
                    + (row[1].startsWith("northfield") ? "" : "&openingBalance=" + row[4] + "&closingBalance=" + row[5]);
            expected.add("POST /api/accounts/" + id + "/imports?" + query);
        }
        assertThat(lines()).containsExactlyElementsOf(expected);

        // the accounts as the answer key expects them, in order
        assertThat(calls.stream().filter(c -> c.line().equals("POST /api/accounts"))
                .map(c -> JSON.readTree(c.body())).map(a -> a.get("name").stringValue() + "|"
                        + a.get("institution").stringValue() + "|" + a.get("type").stringValue() + "|"
                        + a.get("lastFour").stringValue()))
                .containsExactly("Northfield Bank Checking|Northfield Bank|CHECKING|1001",
                        "Northfield Bank Savings|Northfield Bank|SAVINGS|1002", "Apex Rewards Visa|Apex|CREDIT_CARD|2001",
                        "Summit Cash Card|Summit|CREDIT_CARD|3001");
        // every file sent is the manifest's, by name, in order
        assertThat(calls.stream().filter(c -> c.target().contains("/imports?")).map(c -> fileName(c.body())))
                .containsExactlyElementsOf(manifest().stream().map(r -> Path.of(r[0]).getFileName().toString()).toList());

        // the per-file report: imported, already imported, the broken June file in the review queue as expected
        assertThat(run.output())
                .contains("statements/northfield-checking_2026-04.csv   imported: 12 transactions, 0 duplicates, "
                        + "balance check PASSED")
                .contains("statements/northfield-savings_2026-05.csv    already imported")
                .contains("broken/summit-card_2026-06.csv               review queue: the statement needs review "
                        + "(balance check FAILED) (expected: the deliberately broken June file)")
                .contains("Files: 24 imported, 1 already imported, 1 in the review queue, 0 refused")
                .doesNotContain("Categorizing");
    }

    @Test
    void theSuggestedMappingAndSignAreSavedOncePerAccountRightAfterItsFirstPreview() throws Exception {
        Run run = run("");

        assertThat(run.exit()).as(run.output()).isZero();
        List<Call> puts = calls.stream().filter(c -> c.method().equals("PUT")).toList();
        assertThat(puts).hasSize(4);
        for (Call put : puts) {
            assertThat(JSON.readTree(put.body())).isEqualTo(JSON.readTree(MAPPING));
            Call before = calls.get(calls.indexOf(put) - 1);
            assertThat(before.target()).isEqualTo(put.target().replace("csv-mapping", "imports/preview"));
            assertThat(fileName(before.body())).endsWith("_2026-04.csv");
        }
        assertThat(run.output()).contains("northfield-checking: the suggested CSV mapping saved (sign NEGATIVE_IS_DEBIT)");
    }

    /** It never adds sample data next to real statements, and a second run on a loaded vault stops here too. */
    @Test
    void aVaultWithAnyAccountIsRefusedAndNothingIsWritten() throws Exception {
        hasAccounts = true;

        Run run = run("");

        assertThat(run.exit()).isNotZero();
        assertThat(run.output()).contains("this vault already has accounts").contains("nothing was changed");
        assertThat(lines()).containsExactly("GET /api/status", "GET /api/accounts");
    }

    @Test
    void anUnlockedVaultIsUsedAsItIsAndNoPasswordIsRead() throws Exception {
        Run run = run(PASSWORD + "\n");

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(lines()).doesNotContain("POST /api/vault/unlock");
        assertThat(run.output()).contains("Vault: unlocked");
    }

    /** The password goes in the unlock request's body only: in no argument of curl or plutil, and not in the output. */
    @Test
    void thePasswordIsInTheUnlockBodyOnlyNeverAnArgumentOrPrinted() throws Exception {
        locked = true;

        Run run = run(PASSWORD + "\n");

        assertThat(run.exit()).as(run.output()).isZero();
        JsonNode unlock = JSON.readTree(calls.stream().filter(c -> c.line().equals("POST /api/vault/unlock"))
                .findFirst().orElseThrow().body());
        assertThat(unlock.get("password").stringValue()).isEqualTo(PASSWORD);
        assertThat(run.arguments()).isNotBlank().doesNotContain("battery").doesNotContain("staple");
        assertThat(run.output()).doesNotContain("battery").doesNotContain("staple");
    }

    @Test
    void aWrongPasswordStopsBeforeAnythingIsWritten() throws Exception {
        locked = true;

        Run run = run("not the password\n");

        assertThat(run.exit()).isNotZero();
        assertThat(run.output()).contains("unlock refused: HTTP 401: The password is wrong.")
                .doesNotContain("not the password");
        assertThat(lines()).containsExactly("GET /api/status", "POST /api/vault/unlock");
    }

    @Test
    void categorizationRunsOnlyWhenAskedAndItsSummaryIsPrinted() throws Exception {
        Run run = run("", "--categorize");

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(lines().getLast()).isEqualTo("POST /api/categorization/run?ai=true");
        assertThat(run.output()).contains("Categorization: AI DONE; 140 categorized, 3 to review, 2 held back, "
                + "6 model calls, 40 merchants indexed");
    }

    /** Loopback only: a port can be chosen, never a host; the help says what --categorize sends. */
    @Test
    void onlyAPortCanBeChosenAndTheHelpSaysWhatCategorizeSends() throws Exception {
        assertThat(run("", "--host", "example.com").exit()).isNotZero();
        assertThat(run("", "--password", "x").exit()).isNotZero();
        Run help = run("", "--help");
        assertThat(help.exit()).isZero();
        assertThat(help.output()).contains("http://127.0.0.1").contains("Another host is never used")
                .contains("Amazon Bedrock (paid calls)").contains("password read from stdin");
        assertThat(calls).isEmpty();
        assertThat(Files.readString(SCRIPT)).doesNotContain("--host").contains("http://127.0.0.1:");
    }

    @Test
    void aPortThatIsNotANumberIsRefused() throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", SCRIPT.toString(), "--port", "80;rm").redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();

        assertThat(process.exitValue()).isNotZero();
        assertThat(output).contains("--port must be 1 to 65535");
    }
}
