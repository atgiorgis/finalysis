package com.finalysis.ai.bedrock;

import com.finalysis.ai.PromptCache;
import java.time.Duration;
import org.springframework.ai.model.bedrock.autoconfigure.BedrockAwsConnectionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * The Bedrock chat client, built here only to set its retry strategy (docs/plans/phase-4.md step 7 notes). Spring AI's
 * Bedrock Converse model uses a {@link BedrockRuntimeClient} bean when one exists, and builds its own otherwise, with
 * the SDK's default (legacy) retries. Spring AI's {@code RetryTemplate} doesn't apply to Bedrock Converse, and no
 * retry layer is added on top of the SDK's: transport retries happen here only.
 *
 * <p>The SDK's standard strategy with at most {@value #MAX_ATTEMPTS} attempts (the first call and two retries), with
 * its exponential backoff and jitter, for what the SDK classes as retryable: throttling, 5xx and transient network
 * errors. A client error (4xx other than throttling) is never retried. The whole call, retries included, is bounded by
 * {@code spring.ai.bedrock.aws.timeout}. Everything else is as Spring AI would build it: the same credentials and region
 * beans (so the test suite's refusing credentials still apply), Apache HTTP client and timeouts.
 *
 * <p>The only code that names the provider: active only when {@code spring.ai.model.chat} is {@code bedrock-converse}
 * (the {@code aws} profile), and nothing else may depend on this package ({@code PackageDependencyTest}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "bedrock-converse")
public class BedrockClientConfiguration {

    public static final int MAX_ATTEMPTS = 3;

    @Bean
    BedrockRuntimeClient bedrockRuntimeClient(AwsCredentialsProvider credentials, AwsRegionProvider region,
                                              BedrockAwsConnectionProperties connection) {
        ApacheHttpClient.Builder http = ApacheHttpClient.builder();
        if (connection.getConnectionAcquisitionTimeout() != null) {
            http.connectionAcquisitionTimeout(connection.getConnectionAcquisitionTimeout());
        }
        if (connection.getConnectionTimeout() != null) {
            http.connectionTimeout(connection.getConnectionTimeout());
        }
        if (connection.getSocketTimeout() != null) {
            http.socketTimeout(connection.getSocketTimeout());
        }
        return client(credentials, region.getRegion(), connection.getTimeout(), http.build());
    }

    /** Cache points for chat's fixed prefix (docs/plans/phase-6.md step 8c). */
    @Bean
    PromptCache bedrockPromptCache() {
        return new BedrockPromptCache();
    }

    /** The client with this retry strategy; tests pass a fake HTTP client. */
    public static BedrockRuntimeClient client(AwsCredentialsProvider credentials, Region region, Duration callTimeout,
                                              SdkHttpClient http) {
        return BedrockRuntimeClient.builder()
                .region(region)
                .credentialsProvider(credentials)
                .httpClient(http)
                .overrideConfiguration(override -> {
                    override.retryStrategy(retryStrategy());
                    if (callTimeout != null) {
                        override.apiCallTimeout(callTimeout);
                    }
                })
                .build();
    }

    /** The SDK's standard strategy, at most {@value #MAX_ATTEMPTS} attempts. */
    public static RetryStrategy retryStrategy() {
        return AwsRetryStrategy.standardRetryStrategy().toBuilder().maxAttempts(MAX_ATTEMPTS).build();
    }
}
