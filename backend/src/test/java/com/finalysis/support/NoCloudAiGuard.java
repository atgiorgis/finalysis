package com.finalysis.support;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
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
 * <p>The two exceptions are opt-in: the live smoke test, a class tagged {@value #LIVE_TAG} run with
 * {@code -D}{@value #LIVE_PROPERTY}{@code =true} (the {@code ai-smoke} Maven profile sets both), and the accuracy
 * evaluation, a class tagged {@value #EVAL_TAG} run with {@code -D}{@value #EVAL_PROPERTY}{@code =true} (the
 * {@code ai-eval} profile). A tag without its own property, or a property without its own tag, changes nothing.
 *
 * <p>Registered for every Spring test context in {@code META-INF/spring.factories}, like {@link DevDatabaseGuard}.
 */
public class NoCloudAiGuard implements ContextCustomizerFactory {

    public static final String LIVE_TAG = "live-ai";
    public static final String LIVE_PROPERTY = "finalysis.live-ai";
    public static final String EVAL_TAG = "ai-eval";
    public static final String EVAL_PROPERTY = "finalysis.ai-eval";

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return new Customizer(isLive(testClass));
    }

    /** Whether this test class may call a cloud model now: its tag and its own switch are both on. */
    static boolean isLive(Class<?> testClass) {
        return tagged(testClass, LIVE_TAG) && Boolean.getBoolean(LIVE_PROPERTY)
                || tagged(testClass, EVAL_TAG) && Boolean.getBoolean(EVAL_PROPERTY);
    }

    private static boolean tagged(Class<?> testClass, String name) {
        return AnnotatedElementUtils.findAllMergedAnnotations(testClass, Tag.class).stream()
                .anyMatch(tag -> name.equals(tag.value()));
    }

    /** Thrown instead of credentials: the request is never signed, so it is never sent. */
    public static final class CloudCallBlockedException extends IllegalStateException {

        CloudCallBlockedException() {
            super("Tests never call a cloud model; run the live smoke test with -Pai-smoke, the evaluation with "
                    + "-Pai-eval");
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
        }
    }
}
