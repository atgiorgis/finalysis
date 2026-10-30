package com.finalysis.ai.selfhost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.support.IntegrationTest;
import com.finalysis.support.NoCloudAiGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.ec2.Ec2Client;

/**
 * Under the {@code aws} profile the Self-hosted controls have credentials (docs/plans/phase-9.md §5.4): the EC2 client
 * is built for the chosen region with the profile's credentials and a 10-second call limit. In the test suite those
 * credentials are {@link NoCloudAiGuard}'s, which refuse, so the DescribeInstances request fails while it is signed,
 * before anything is sent, and the status says so in plain words.
 */
@IntegrationTest
@AutoConfigureMockMvc
@ActiveProfiles("aws")
class SelfHostAwsProfileTest {

    private final MockMvc mvc;
    private final AwsEc2Clients clients;
    private final AwsCredentialsProvider credentials;
    private final JdbcTemplate jdbc;

    SelfHostAwsProfileTest(MockMvc mvc, AwsEc2Clients clients, AwsCredentialsProvider credentials,
                           JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.clients = clients;
        this.credentials = credentials;
        this.jdbc = jdbc;
    }

    /** The path lives in the shared test database: leave none behind for the next context's unlock. */
    @AfterEach
    void forgetThePath() {
        jdbc.update("DELETE FROM app_setting WHERE key LIKE 'ai.%'");
    }

    @Test
    void theEc2ClientIsTheRegionsWithTheProfilesCredentialsAndATimeout() {
        assertThat(clients.available()).isTrue();
        Ec2Client client = clients.client("eu-west-1");

        assertThat(client.serviceClientConfiguration().region().id()).isEqualTo("eu-west-1");
        assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
                .contains(java.time.Duration.ofSeconds(10));
        assertThat(clients.client("eu-west-1")).as("one client per region").isSameAs(client);
    }

    @Test
    void aRequestIsRefusedWhileSignedAndTheStatusSaysSoInPlainWords() throws Exception {
        NoCloudAiGuard.RefusingCredentialsProvider refusing = (NoCloudAiGuard.RefusingCredentialsProvider) credentials;
        int before = refusing.requests();
        mvc.perform(put("/api/settings/ai").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\": \"OFF\", \"selfhostRegion\": \"us-east-1\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/selfhost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controls").value(false))
                .andExpect(jsonPath("$.reason").value(
                        "Couldn't reach AWS for DescribeInstances: no credentials, or no network."));
        mvc.perform(post("/api/selfhost/start"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:finalysis:problem:selfhost-unavailable"));

        assertThat(refusing.requests()).as("the guard refused the signing").isGreaterThan(before);
    }
}
