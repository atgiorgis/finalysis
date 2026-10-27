package com.finalysis.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.support.TestcontainersConfiguration;
import com.finalysis.vault.VaultFakes;
import com.finalysis.vault.VaultFakes.FakeDatabaseRuntime;
import com.finalysis.vault.VaultFakes.FakeVaultMount;
import com.finalysis.vault.VaultFakes.VaultEvents;
import com.finalysis.vault.VaultProperties;
import com.finalysis.vault.VaultService;
import com.finalysis.vault.VaultState;
import com.finalysis.vault.VaultTestState;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phone access end to end (docs/plans/phase-8.md §9, §10 "Login", "HTTPS"): a real server, the vault on (fakes for the
 * image and the database runtime), the LAN listener on 127.0.0.1 (loopback is allowed in tests only, so no test opens a
 * port on the network) and an HTTPS client that trusts only the CA the app hands out. Covers the setting, the CA and
 * its SAN, plain HTTP refused, login with its cookie flags and Origin check, the session, the UI served on the
 * listener, lock ending every session and closing the listener, and unlock opening it again.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "finalysis.vault.enabled=true",
        "finalysis.vault.dir=${user.dir}/target/vault-test-${random.uuid}",
        "finalysis.access.loopback-for-tests=true"})
@Import({TestcontainersConfiguration.class, VaultFakes.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class PhoneAccessTest {

    private static final String PASSWORD = "phone access test password";
    private static final Path UI;

    static {
        try {
            UI = Files.createTempDirectory("finalysis-ui");
            Files.writeString(UI.resolve("index.html"), "<!doctype html><title>FINALYSIS-UI</title>");
            Files.createDirectories(UI.resolve("assets"));
            Files.writeString(UI.resolve("assets/app.js"), "console.log('FINALYSIS-JS')");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void ui(DynamicPropertyRegistry registry) {
        registry.add("finalysis.access.ui-dir", UI::toString);
    }

    @LocalServerPort
    int localPort;

    private final VaultService vault;
    private final VaultProperties properties;
    private final FakeVaultMount mount;
    private final FakeDatabaseRuntime runtime;
    private final VaultEvents events;
    private final LanSessions sessions;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient local = HttpClient.newHttpClient();
    private int lanPort;

    PhoneAccessTest(VaultService vault, VaultProperties properties, FakeVaultMount mount, FakeDatabaseRuntime runtime,
                    VaultEvents events, LanSessions sessions) {
        this.sessions = sessions;
        this.vault = vault;
        this.properties = properties;
        this.mount = mount;
        this.runtime = runtime;
        this.events = events;
    }

    @BeforeEach
    void unlocked() throws IOException {
        VaultTestState.reset(vault, properties, mount, runtime, events);
        lanPort = freePort();
        try {
            assertThat(sendLocal("POST", "/api/vault/setup", "{\"password\":\"" + PASSWORD + "\"}").statusCode())
                    .isEqualTo(201);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void phoneAccessOff() throws Exception {
        if (vault.state() != VaultState.UNLOCKED) {
            unlock();
        }
        sendLocal("PUT", "/api/access", "{\"enabled\":false}");
    }

    @Test
    void theSettingTakesOnlyAPrivateAddressOfThisMachineAndAFreePort() throws Exception {
        JsonNode off = json.readTree(sendLocal("GET", "/api/access", null).body());
        assertThat(off.get("enabled").asBoolean()).isFalse();
        assertThat(off.get("open").asBoolean()).isFalse();
        assertThat(off.get("availableAddresses").toString()).contains("127.0.0.1");

        assertThat(sendLocal("PUT", "/api/access", setting("8.8.8.8", lanPort)).statusCode()).isEqualTo(422);
        assertThat(sendLocal("PUT", "/api/access", setting("192.168.255.254", lanPort)).statusCode())
                .as("private, but not this machine's").isEqualTo(422);
        assertThat(sendLocal("PUT", "/api/access", setting("127.0.0.1", localPort)).statusCode())
                .as("the app's own port").isEqualTo(422);
        assertThat(sendLocal("PUT", "/api/access", setting("127.0.0.1", 80)).statusCode()).isEqualTo(422);
        assertThat(sendLocal("GET", "/api/access/ca.cer", null).statusCode()).as("no CA before it's on")
                .isEqualTo(404);
    }

    @Test
    void theListenerServesHttpsWithTheAppsOwnCaAndRefusesPlainHttp() throws Exception {
        JsonNode on = turnOn();
        assertThat(on.get("open").asBoolean()).isTrue();
        assertThat(on.get("url").asString()).isEqualTo("https://127.0.0.1:" + lanPort);

        HttpResponse<byte[]> download = local.send(HttpRequest.newBuilder(localUri("/api/access/ca.cer")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.headers().firstValue("Content-Disposition").orElse("")).contains("finalysis-local-ca.cer");
        assertThat(download.headers().firstValue("Cache-Control")).contains("no-store");
        X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(download.body()));
        assertThat(LanCertificates.fingerprint(ca)).isEqualTo(on.get("caFingerprint").asString());
        assertThat(LanCertificates.permits(ca, new LanAddresses(true).range("127.0.0.1").orElseThrow())).isTrue();

        HttpClient phone = phoneTrusting(ca);
        HttpResponse<String> session = phone.send(lan("GET", "/api/access/session", null, null),
                HttpResponse.BodyHandlers.ofString());
        assertThat(json.readTree(session.body()).get("lan").asBoolean()).isTrue();
        assertThat(json.readTree(session.body()).get("authenticated").asBoolean()).isFalse();
        X509Certificate server = (X509Certificate) session.sslSession().orElseThrow().getPeerCertificates()[0];
        assertThat(LanCertificates.namesIp(server, "127.0.0.1")).isTrue();
        assertThat(server.getNotAfter().toInstant()).isBefore(Instant.now().plus(Duration.ofDays(398)));

        assertThatThrownBy(() -> HttpClient.newHttpClient().send(lan("GET", "/api/access/session", null, null),
                HttpResponse.BodyHandlers.ofString())).as("a client that doesn't trust the app's CA")
                .isInstanceOf(SSLHandshakeException.class);

        String plain;
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + lanPort + "/api/access/session")).build(),
                    HttpResponse.BodyHandlers.ofString());
            plain = response.statusCode() + " " + response.body();
        } catch (IOException e) {
            plain = "refused";
        }
        assertThat(plain).as("plain HTTP on the LAN port").doesNotContain("\"lan\"").doesNotStartWith("200");
    }

    @Test
    void loginTheSessionAndTheUiOnTheListener() throws Exception {
        X509Certificate ca = caOf(turnOn());
        HttpClient phone = phoneTrusting(ca);
        String origin = "https://127.0.0.1:" + lanPort;

        assertThat(phone.send(lan("GET", "/api/accounts", null, null), HttpResponse.BodyHandlers.ofString()).body())
                .contains("urn:finalysis:problem:login-required");
        assertThat(phone.send(lan("GET", "/", null, null), HttpResponse.BodyHandlers.ofString()).body())
                .contains("FINALYSIS-UI");
        assertThat(phone.send(lan("GET", "/transactions", null, null), HttpResponse.BodyHandlers.ofString()).body())
                .as("a route of the app is its index.html").contains("FINALYSIS-UI");
        assertThat(phone.send(lan("GET", "/assets/app.js", null, null), HttpResponse.BodyHandlers.ofString()).body())
                .contains("FINALYSIS-JS");
        assertThat(sendLocal("GET", "/", null).body()).as("not served on localhost").doesNotContain("FINALYSIS-UI");

        String loginBody = "{\"password\":\"" + PASSWORD + "\"}";
        assertThat(phone.send(lan("POST", "/api/access/login", loginBody, null), HttpResponse.BodyHandlers.ofString())
                .statusCode()).as("no Origin").isEqualTo(403);
        assertThat(phone.send(lan("POST", "/api/access/login", loginBody, "https://attacker.example"),
                HttpResponse.BodyHandlers.ofString()).statusCode()).as("a foreign Origin").isEqualTo(403);
        HttpResponse<String> wrong = phone.send(lan("POST", "/api/access/login", "{\"password\":\"not it\"}", origin),
                HttpResponse.BodyHandlers.ofString());
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).contains("wrong-password").doesNotContain("not it");

        HttpResponse<String> ok = phone.send(lan("POST", "/api/access/login", loginBody, origin),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(204);
        String setCookie = ok.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(setCookie).startsWith("__Host-finalysis=").contains("Secure").contains("HttpOnly")
                .containsIgnoringCase("SameSite=Strict").contains("Path=/").doesNotContainIgnoringCase("Domain=");
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));

        HttpResponse<String> accounts = phone.send(withCookie(lan("GET", "/api/accounts", null, null), cookie),
                HttpResponse.BodyHandlers.ofString());
        assertThat(accounts.statusCode()).isEqualTo(200);
        assertThat(accounts.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(phone.send(withCookie(lan("GET", "/api/access", null, null), cookie),
                HttpResponse.BodyHandlers.ofString()).statusCode()).as("the setting is the computer's").isEqualTo(403);
        assertThat(json.readTree(sendLocal("GET", "/api/access", null).body()).get("sessions").asInt()).isEqualTo(1);
        assertThat(sendLocal("GET", "/api/accounts", null).headers().firstValue("Set-Cookie"))
                .as("localhost stays stateless").isEmpty();
    }

    @Test
    void logoutFromThePhoneIsLockEndingEverySessionAndClosingTheListenerUntilUnlock() throws Exception {
        X509Certificate ca = caOf(turnOn());
        HttpClient phone = phoneTrusting(ca);
        String origin = "https://127.0.0.1:" + lanPort;
        HttpResponse<String> ok = phone.send(lan("POST", "/api/access/login", "{\"password\":\"" + PASSWORD + "\"}",
                origin), HttpResponse.BodyHandlers.ofString());
        String cookie = ok.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];

        HttpResponse<String> lock = phone.send(withCookie(lan("POST", "/api/vault/lock", "", origin), cookie),
                HttpResponse.BodyHandlers.ofString());

        assertThat(lock.statusCode()).isEqualTo(204);
        assertThat(vault.state()).isEqualTo(VaultState.LOCKED);
        assertThat(sessions.count()).as("every session ended by the lock").isZero();
        Instant deadline = Instant.now().plusSeconds(5);
        boolean closed = false;
        while (!closed && Instant.now().isBefore(deadline)) {
            try (java.net.Socket socket = new java.net.Socket(InetAddress.getLoopbackAddress(), lanPort)) {
                Thread.sleep(100);
            } catch (IOException refused) {
                closed = true;
            }
        }
        assertThat(closed).as("the listener closes with the lock").isTrue();

        unlock();
        HttpResponse<String> after = phone.send(withCookie(lan("GET", "/api/accounts", null, null), cookie),
                HttpResponse.BodyHandlers.ofString());
        assertThat(after.statusCode()).as("open again after unlock, but the old session is gone").isEqualTo(401);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private void unlock() throws Exception {
        assertThat(sendLocal("POST", "/api/vault/unlock", "{\"password\":\"" + PASSWORD + "\"}").statusCode())
                .isEqualTo(204);
    }

    private JsonNode turnOn() throws Exception {
        HttpResponse<String> response = sendLocal("PUT", "/api/access", setting("127.0.0.1", lanPort));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode status = json.readTree(response.body());
        assertThat(status.get("open").asBoolean()).as(response.body()).isTrue();
        return status;
    }

    private X509Certificate caOf(JsonNode status) throws Exception {
        byte[] der = local.send(HttpRequest.newBuilder(localUri("/api/access/ca.cer")).build(),
                HttpResponse.BodyHandlers.ofByteArray()).body();
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static String setting(String address, int port) {
        return "{\"enabled\":true,\"address\":\"" + address + "\",\"port\":" + port + "}";
    }

    private HttpResponse<String> sendLocal(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(localUri(path)).header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return local.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI localUri(String path) {
        return URI.create("http://localhost:" + localPort + path);
    }

    private HttpRequest lan(String method, String path, String body, String origin) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + lanPort + path))
                .timeout(Duration.ofSeconds(10));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return request.build();
    }

    private static HttpRequest withCookie(HttpRequest request, String cookie) {
        return HttpRequest.newBuilder(request, (name, value) -> true).header("Cookie", cookie).build();
    }

    /** A client that trusts only this CA, as the phone does once it is installed. */
    private static HttpClient phoneTrusting(X509Certificate ca) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("finalysis-ca", ca);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(context).build();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
