package com.finalysis.ai.selfhost;

import com.finalysis.ai.AiProperties;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;

/**
 * The app's EC2 clients (docs/plans/phase-9.md §5.4), one per region, signed with the {@code aws} profile's credentials
 * (the {@code finalysis-dev} identity, which may only Start and Stop the tagged instance and Describe). Without the
 * profile ({@code finalysis.ai.cloud-available} false) there are none, and Start and Stop are disabled. The test suite's
 * refusing credentials ({@code NoCloudAiGuard}) apply here too: a request fails while it is signed, before it is sent.
 */
@Component
class AwsEc2Clients implements Ec2Clients {

    private final AiProperties ai;
    private final ObjectProvider<AwsCredentialsProvider> credentials;
    private final SelfHostProperties properties;
    private final Map<String, Ec2Client> clients = new ConcurrentHashMap<>();

    AwsEc2Clients(AiProperties ai, ObjectProvider<AwsCredentialsProvider> credentials, SelfHostProperties properties) {
        this.ai = ai;
        this.credentials = credentials;
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return ai.cloudAvailable() && credentials.getIfAvailable() != null;
    }

    @Override
    public Ec2Client client(String region) {
        if (!available()) {
            throw new IllegalStateException("no Cloud credentials");
        }
        AwsCredentialsProvider provider = credentials.getObject();
        return clients.computeIfAbsent(region, r -> Ec2Client.builder()
                .region(Region.of(r))
                .credentialsProvider(provider)
                .httpClientBuilder(ApacheHttpClient.builder())
                .overrideConfiguration(c -> c.apiCallTimeout(properties.awsTimeout()))
                .build());
    }

    @PreDestroy
    void close() {
        clients.values().forEach(Ec2Client::close);
        clients.clear();
    }
}
