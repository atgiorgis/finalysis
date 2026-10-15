package com.finalysis.ai.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeException;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;

/**
 * The Bedrock client's transport retries (docs/plans/phase-4.md step 7 notes): the SDK's standard strategy, at most
 * three attempts, for throttling and 5xx only. Against an in-process HTTP client that answers with canned errors and
 * counts requests: nothing leaves the machine, and the credentials are dummies that sign nothing real.
 */
class BedrockClientConfigurationTest {

    /** Answers every request with one status and error type, and counts them. */
    static final class CannedHttpClient implements SdkHttpClient {

        final AtomicInteger requests = new AtomicInteger();
        private final int status;
        private final String errorType;

        CannedHttpClient(int status, String errorType) {
            this.status = status;
            this.errorType = errorType;
        }

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() {
                    requests.incrementAndGet();
                    byte[] body = "{\"message\":\"canned\"}".getBytes(StandardCharsets.UTF_8);
                    return HttpExecuteResponse.builder()
                            .response(SdkHttpResponse.builder().statusCode(status)
                                    .headers(Map.of("x-amzn-ErrorType", List.of(errorType),
                                            "Content-Type", List.of("application/json")))
                                    .build())
                            .responseBody(AbortableInputStream.create(new ByteArrayInputStream(body)))
                            .build();
                }

                @Override
                public void abort() {
                }
            };
        }

        @Override
        public String clientName() {
            return "canned";
        }

        @Override
        public void close() {
        }
    }

    private static int attempts(int status, String errorType) {
        CannedHttpClient http = new CannedHttpClient(status, errorType);
        try (BedrockRuntimeClient client = BedrockClientConfiguration.client(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key-id", "test-secret")),
                Region.US_EAST_1, Duration.ofSeconds(30), http)) {
            assertThatThrownBy(() -> client.converse(request -> request.modelId("test-model")
                    .messages(message -> message.role(ConversationRole.USER).content(c -> c.text("synthetic")))))
                    .isInstanceOf(BedrockRuntimeException.class);
        }
        return http.requests.get();
    }

    @Test
    void theStrategyIsTheSdksStandardOneWithThreeAttempts() {
        assertThat(BedrockClientConfiguration.retryStrategy().maxAttempts()).isEqualTo(3);
        assertThat(BedrockClientConfiguration.MAX_ATTEMPTS).isEqualTo(3);
    }

    @Test
    void throttlingIsTriedThreeTimes() {
        assertThat(attempts(429, "ThrottlingException")).isEqualTo(3);
    }

    @Test
    void aServerErrorIsTriedThreeTimes() {
        assertThat(attempts(503, "ServiceUnavailableException")).isEqualTo(3);
        assertThat(attempts(500, "InternalServerException")).isEqualTo(3);
    }

    @Test
    void aClientErrorIsNotRetried() {
        assertThat(attempts(400, "ValidationException")).isEqualTo(1);
        assertThat(attempts(403, "AccessDeniedException")).isEqualTo(1);
    }
}
