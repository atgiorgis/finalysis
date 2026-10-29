package com.finalysis.support;

import com.finalysis.ai.OllamaHostGuard;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/**
 * The test suite never calls a cloud model (docs/plans/phase-4.md §10). Every Spring test context gets an
 * {@link AwsCredentialsProvider} that refuses, so a context with the {@code aws} profile builds its Bedrock clients
 * but any request fails while it is being signed, before anything is sent. Spring AI's own provider (which reads
 * the AWS CLI profile) backs off, since it is only created when none exists.
 *
 * <p>The three exceptions are opt-in: the live smoke tests, a class tagged {@value #LIVE_TAG} run with
 * {@code -D}{@value #LIVE_PROPERTY}{@code =true} (the {@code ai-smoke} Maven profile sets both), the categorization
 * accuracy evaluation, a class tagged {@value #EVAL_TAG} run with {@code -D}{@value #EVAL_PROPERTY}{@code =true} (the
 * {@code ai-eval} profile), and the chat evaluation (docs/plans/phase-6.md step 8), a class tagged
 * {@value #CHAT_EVAL_TAG} run with {@code -D}{@value #CHAT_EVAL_PROPERTY}{@code =true} (the {@code chat-eval} profile).
 * A tag without its own property, or a property without its own tag, changes nothing.
 *
 * <p>Ollama (docs/plans/phase-9.md §4.3): every context but a live one gets an {@link OllamaHostGuard} that fails the
 * test ({@link OllamaCallBlockedError}) on any Ollama request whose host isn't loopback on a port a test's stub server
 * registered ({@link #allowStub}), so no test reaches a real Ollama on this machine or a server. It also selects
 * {@code finalysis.ai.default-path=OFF} at the lowest precedence: a fresh test vault has no AI unless the test chooses
 * a path (a {@code @TestPropertySource} or a switch), so no test depends on whether Ollama runs here. The shipped default
 * is Local ({@code AiDefaultPathTest}).
 *
 * <p>Registered for every Spring test context in {@code META-INF/spring.factories}, like {@link DevDatabaseGuard}.
 */
public class NoCloudAiGuard implements ContextCustomizerFactory {

    public static final String LIVE_TAG = "live-ai";
    public static final String LIVE_PROPERTY = "finalysis.live-ai";
    public static final String EVAL_TAG = "ai-eval";
    public static final String EVAL_PROPERTY = "finalysis.ai-eval";
    public static final String CHAT_EVAL_TAG = "chat-eval";
    public static final String CHAT_EVAL_PROPERTY = "finalysis.chat-eval";

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return new Customizer(isLive(testClass));
    }

    /** Whether this test class may call a cloud model now: its tag and its own switch are both on. */
    static boolean isLive(Class<?> testClass) {
        return tagged(testClass, LIVE_TAG) && Boolean.getBoolean(LIVE_PROPERTY)
                || tagged(testClass, EVAL_TAG) && Boolean.getBoolean(EVAL_PROPERTY)
                || tagged(testClass, CHAT_EVAL_TAG) && Boolean.getBoolean(CHAT_EVAL_PROPERTY);
    }

    private static boolean tagged(Class<?> testClass, String name) {
        return AnnotatedElementUtils.findAllMergedAnnotations(testClass, Tag.class).stream()
                .anyMatch(tag -> name.equals(tag.value()));
    }

    /** The test property this guard sets, at the lowest precedence. */
    public static final String DEFAULT_PATH_PROPERTY = "finalysis.ai.default-path";

    private static final Set<Integer> STUB_PORTS = ConcurrentHashMap.newKeySet();

    /** Lets Ollama requests reach a test's stub server on loopback at this port. */
    public static void allowStub(int port) {
        STUB_PORTS.add(port);
    }

    /** Stops letting requests reach this port (the stub stopped). */
    public static void forgetStub(int port) {
        STUB_PORTS.remove(port);
    }

    /** Thrown instead of an Ollama request to anything but a test's stub: an Error, so no catch turns it into "unreachable". */
    public static final class OllamaCallBlockedError extends AssertionError {

        OllamaCallBlockedError(URI request) {
            super("Tests never call a real Ollama: " + request.getScheme() + "://" + request.getHost() + ":"
                    + request.getPort() + " isn't a test's stub server on loopback");
        }
    }

    /** Lets only a registered stub on loopback through. */
    public static final class StubOnlyOllamaHosts implements OllamaHostGuard {

        @Override
        public void check(URI request) {
            String host = request.getHost();
            boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host);
            if (!loopback || !STUB_PORTS.contains(request.getPort())) {
                throw new OllamaCallBlockedError(request);
            }
        }
    }

    /** Thrown instead of credentials: the request is never signed, so it is never sent. */
    public static final class CloudCallBlockedException extends IllegalStateException {

        CloudCallBlockedException() {
            super("Tests never call a cloud model; run the live smoke test with -Pai-smoke, the evaluation with "
                    + "-Pai-eval, the chat evaluation with -Pchat-eval");
        }
    }

    /** One per test context; counts the requests it refused. */
    public static final class RefusingCredentialsProvider implements AwsCredentialsProvider {

        private final AtomicInteger requests = new AtomicInteger();

        /** How many times this context asked for cloud credentials, i.e. tried to send a request. */
        public int requests() {
            return requests.get();
        }

        @Override
        public AwsCredentials resolveCredentials() {
            requests.incrementAndGet();
            throw new CloudCallBlockedException();
        }
    }

    // A record, so equal customizers keep Spring's test context cache working.
    record Customizer(boolean live) implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            if (live) {
                return;
            }
            if (Arrays.asList(context.getBeanFactory().getBeanNamesForType(AwsCredentialsProvider.class)).isEmpty()) {
                context.getBeanFactory().registerSingleton("noCloudAiCredentials", new RefusingCredentialsProvider());
            }
            context.getBeanFactory().registerSingleton("noRealOllama", new StubOnlyOllamaHosts());
            context.getEnvironment().getPropertySources().addLast(new MapPropertySource("noCloudAiDefaults",
                    Map.of(DEFAULT_PATH_PROPERTY, "OFF")));
        }
    }
}
