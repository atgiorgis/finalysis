package com.finalysis.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.finalysis.ai.ollama.HostResolver;
import com.finalysis.ai.ollama.OllamaProviderFactory;
import com.finalysis.chat.ChatModelClient;
import com.finalysis.chat.ChatToolCallbacks;
import com.finalysis.chat.tool.ToolRegistryTestSupport;
import com.finalysis.support.OllamaStub;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Ollama's requests go to a host name's IPv4 address (docs/plans/phase-9.md, 9c-8 notes). Tailscale gives the server
 * an IPv4 and an IPv6 address, Java tried the IPv6 one first, and the server's Ollama listens on IPv4 only. Here the
 * stub listens on {@code 127.0.0.1} only, like the server, and a stub resolver answers for the name, so a request sent
 * to {@code ::1} is refused just as the owner's was. No real name is looked up: the name ends in {@code .invalid},
 * which never resolves (RFC 6761), and the guard lets only it and the stub's loopback port through.
 */
class OllamaIpv4Test {

    private static final String NAME = "finalysis-selfhost.invalid";
    private static final String CHAT_MODEL = "test-ollama-chat";
    private static final String EMBEDDING_MODEL = "nomic-embed-text";

    private OllamaStub stub;
    private final List<String> lookups = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void start() {
        stub = OllamaStub.start(CHAT_MODEL + ":latest", EMBEDDING_MODEL + ":latest");
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private static InetAddress ipv6Loopback() throws UnknownHostException {
        return InetAddress.getByAddress(NAME, new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
    }

    private static InetAddress ipv4Loopback() throws UnknownHostException {
        return InetAddress.getByAddress(NAME, new byte[] {127, 0, 0, 1});
    }

    /** A factory whose names resolve to these addresses, in this order; none: the name has no address. */
    private OllamaProviderFactory factory(InetAddress... addresses) {
        HostResolver resolver = host -> {
            lookups.add(host);
            if (addresses.length == 0) {
                throw new UnknownHostException(host);
            }
            return addresses;
        };
        OllamaHostGuard guard = request -> {
            String host = request.getHost();
            boolean allowed = NAME.equals(host)
                    || List.of("127.0.0.1", "[0:0:0:0:0:0:0:1]", "[::1]").contains(host) && request.getPort() == stub.port();
            if (!allowed) {
                throw new AssertionError("not the test's name or stub: " + request);
            }
        };
        return new OllamaProviderFactory(new StaticListableBeanFactory(Map.of("guard", guard))
                .getBeanProvider(OllamaHostGuard.class), resolver);
    }

    private static List<Class<?>> causes(Throwable failure) {
        List<Class<?>> classes = new ArrayList<>();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            classes.add(t.getClass());
        }
        return classes;
    }

    private URI named() {
        return URI.create("http://" + NAME + ":" + stub.port());
    }

    @Test
    void theStubLikeTheServerRefusesItsIpv6Address() {
        URI ipv6 = URI.create("http://[::1]:" + stub.port());

        Throwable refused = catchThrowable(() -> factory().version(ipv6, Duration.ofSeconds(2)));

        assertThat(causes(refused)).as("Java's client: a ConnectException").contains(ConnectException.class);
    }

    @Test
    void withAnIpv6AndAnIpv4AddressEveryClientUsesTheIpv4One() throws Exception {
        OllamaProviderFactory factory = factory(ipv6Loopback(), ipv4Loopback());
        ProviderSet set = factory.create(named(), new OllamaProperties.Models(CHAT_MODEL, EMBEDDING_MODEL), 768,
                16_384, Duration.ofSeconds(5));
        stub.then(OllamaStub.text("chat answer"), OllamaStub.text("categorization answer"));

        ActiveProviders active = ActiveProviders.fixed(AiPath.SELF_HOSTED, set);
        ChatRoleProperties role = new ChatRoleProperties(null, true);
        new ChatModelClient(active, new ChatRoleOptions(active, role),
                new ChatToolCallbacks(ToolRegistryTestSupport.registry()), role, new AiPrices(Map.of()))
                .call(List.of(new SystemMessage("system"), new UserMessage("<question>\"Hi\"</question>")), 100, 0);
        set.categorization().model().call(new Prompt("categorize"));
        new SpringAiTextEmbedder(active).embed("QUORVANE BAKEHOUSE");
        OllamaClients.Probe probe = factory.probe(named(), Duration.ofSeconds(2));
        String version = factory.version(named(), Duration.ofSeconds(2));
        factory.warmUp(named(), CHAT_MODEL, 16_384, Duration.ofSeconds(2));

        assertThat(probe.version()).isEqualTo(OllamaStub.VERSION);
        assertThat(version).isEqualTo(OllamaStub.VERSION);
        assertThat(stub.requests()).extracting(OllamaStub.Request::path).containsExactly("/api/chat", "/api/chat",
                "/api/embed", "/api/version", "/api/tags", "/api/version", "/api/generate");
        assertThat(lookups).as("looked up per request").hasSize(7).containsOnly(NAME);
    }

    @Test
    void withAnIpv4AddressOnlyThatOneIsUsed() throws Exception {
        assertThat(factory(ipv4Loopback()).version(named(), Duration.ofSeconds(2))).isEqualTo(OllamaStub.VERSION);
        assertThat(stub.requests("/api/version")).hasSize(1);
    }

    @Test
    void withNoAddressTheNameIsKeptAndTheRequestFailsAsUnresolvable() {
        Throwable failure = catchThrowable(() -> factory().version(named(), Duration.ofSeconds(2)));

        assertThat(causes(failure)).as("Java's client reports an unresolvable name this way")
                .contains(UnresolvedAddressException.class);
        assertThat(lookups).containsExactly(NAME);
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void withAnIpv6AddressOnlyTheNameIsKept() throws Exception {
        OllamaProviderFactory factory = factory(ipv6Loopback());

        assertThat(factory.ipv4Address(named())).isEqualTo(named());
    }

    @Test
    void localsLoopbackAddressIsNeverLookedUp() throws Exception {
        OllamaProviderFactory factory = factory(ipv6Loopback());

        assertThat(factory.version(stub.baseUrl(), Duration.ofSeconds(2))).isEqualTo(OllamaStub.VERSION);
        assertThat(lookups).isEmpty();
        assertThat(factory.ipv4Address(URI.create("http://[::1]:11434"))).asString().isEqualTo("http://[::1]:11434");
        assertThat(lookups).isEmpty();
    }

    @Test
    void onlyTheHostChanges() throws Exception {
        assertThat(factory(ipv6Loopback(), ipv4Loopback()).ipv4Address(URI.create("http://" + NAME + ":11434/api/tags")))
                .asInstanceOf(InstanceOfAssertFactories.type(URI.class))
                .isEqualTo(URI.create("http://127.0.0.1:11434/api/tags"));
    }
}
