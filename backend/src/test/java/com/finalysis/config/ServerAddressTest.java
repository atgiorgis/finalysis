package com.finalysis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.finalysis.support.TestcontainersConfiguration;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * UI-08 (docs/plans/phase-7.md §9): the backend listens on the loopback address only, so nothing else on the network
 * can reach the API until login and HTTPS exist (Phase 8). Starts a real server on a random port and connects to it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ServerAddressTest {

    private static final int TIMEOUT_MS = 2_000;

    private final Environment environment;
    private final int port;

    ServerAddressTest(Environment environment, @LocalServerPort int port) {
        this.environment = environment;
        this.port = port;
    }

    @Test
    void theAddressIsLoopback() {
        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    @Test
    void theServerAnswersOnLoopback() throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), TIMEOUT_MS);
            assertThat(socket.isConnected()).isTrue();
        }
    }

    /** Skipped on a machine with no network address other than loopback (nothing to connect from). */
    @Test
    void theServerRefusesThisMachinesNetworkAddress() throws SocketException {
        Optional<InetAddress> external = externalAddress();
        assumeTrue(external.isPresent(), "no non-loopback IPv4 address on this machine");

        assertThatThrownBy(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(external.get(), port), TIMEOUT_MS);
            }
        }).isInstanceOf(IOException.class);
    }

    /** No profile file may widen the binding: only application.yml sets it. */
    @Test
    void noProfileFileSetsAnotherAddress() throws IOException {
        try (var files = Files.list(Path.of("src/main/resources"))) {
            List<Path> profileFiles = files.filter(p -> p.getFileName().toString().matches("application-.+\\.ya?ml"))
                    .toList();
            assertThat(profileFiles).isNotEmpty();
            for (Path file : profileFiles) {
                assertThat(Files.readString(file)).as(file.toString()).doesNotContain("address:");
            }
        }
    }

    private static Optional<InetAddress> externalAddress() throws SocketException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                    return Optional.of(address);
                }
            }
        }
        return Optional.empty();
    }
}
