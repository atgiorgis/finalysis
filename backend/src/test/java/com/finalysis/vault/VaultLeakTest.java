package com.finalysis.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.finalysis.vault.VaultFakes.FakeDatabaseRuntime;
import com.finalysis.vault.VaultFakes.FakeVaultMount;
import com.finalysis.vault.VaultFakes.VaultEvents;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The vault's leak rules (docs/plans/phase-8.md §8.2, SEC-12): markers planted in the password, a wrong password, the
 * recovery key typed back, a wrong recovery key, the new password at recovery and at password change, through every
 * vault endpoint and each of their refusals. With the app's own packages at DEBUG, no marker and no recovery key
 * reaches the log; no response carries a marker, and the recovery key appears in exactly one response: setup's.
 */
@VaultEnabledTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "logging.level.com.finalysis=DEBUG")
class VaultLeakTest {

    private static final String PASSWORD = "PWMARKER-setup password 1";
    private static final String WRONG = "PWMARKER-wrong password 2";
    private static final String NEW_AT_RECOVERY = "PWMARKER-recovery password 3";
    private static final String NEW_AT_CHANGE = "PWMARKER-changed password 4";
    private static final String SHORT = "PWMARKER-5";
    private static final String MARKER = "PWMARKER";

    private final MockMvc mvc;
    private final JsonMapper json;
    private final VaultService vault;
    private final VaultProperties properties;
    private final FakeVaultMount mount;
    private final FakeDatabaseRuntime runtime;
    private final VaultEvents events;
    private final List<MockHttpServletResponse> responses = new ArrayList<>();

    VaultLeakTest(MockMvc mvc, JsonMapper json, VaultService vault, VaultProperties properties, FakeVaultMount mount,
                  FakeDatabaseRuntime runtime, VaultEvents events) {
        this.mvc = mvc;
        this.json = json;
        this.vault = vault;
        this.properties = properties;
        this.mount = mount;
        this.runtime = runtime;
        this.events = events;
    }

    @BeforeEach
    void notSetUp() {
        VaultTestState.reset(vault, properties, mount, runtime, events);
    }

    @Test
    void noSecretReachesTheLogOrAResponse(CapturedOutput output) throws Exception {
        send("/api/vault/setup", body("password", SHORT));
        MockHttpServletResponse setup = send("/api/vault/setup", body("password", PASSWORD));
        assertThat(setup.getStatus()).isEqualTo(201);
        String key = json.readTree(setup.getContentAsString()).get("recoveryKey").asString();
        send("/api/vault/password", "{\"currentPassword\":\"" + WRONG + "\",\"newPassword\":\""
                + NEW_AT_CHANGE + "\"}");
        send("/api/vault/password", "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + SHORT + "\"}");
        send("/api/vault/password", "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\""
                + NEW_AT_CHANGE + "\"}");
        sendPut("/api/vault/settings", "{\"idleLockMinutes\":\"" + MARKER + "\"}");
        send("/api/vault/lock", "");
        send("/api/vault/unlock", body("password", WRONG));
        send("/api/vault/unlock", body("password", NEW_AT_CHANGE));
        send("/api/vault/lock", "");
        send("/api/vault/recover", "{\"recoveryKey\":\"" + MARKER + "-0000\",\"newPassword\":\""
                + NEW_AT_RECOVERY + "\"}");
        send("/api/vault/recover", "{\"recoveryKey\":\"" + key + "\",\"newPassword\":\"" + SHORT + "\"}");
        send("/api/vault/recover", "{\"recoveryKey\":\"" + key.toLowerCase() + "\",\"newPassword\":\""
                + NEW_AT_RECOVERY + "\"}");
        send("/api/vault/unlock", "{\"password\":"); // malformed: the body is never echoed
        assertThat(vault.state()).isEqualTo(VaultState.UNLOCKED);

        String log = output.getAll();
        assertThat(log).contains("Vault set up and unlocked", "Vault locked", "Vault recovered");
        assertThat(log).doesNotContain(MARKER).doesNotContain(key).doesNotContain(key.replace("-", ""))
                .doesNotContain(key.toLowerCase());
        int carryingTheKey = 0;
        for (MockHttpServletResponse response : responses) {
            String body = response.getContentAsString();
            assertThat(body).as(response.getStatus() + " " + body).doesNotContain(MARKER);
            if (body.contains(key)) {
                carryingTheKey++;
            }
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
        assertThat(carryingTheKey).isEqualTo(1);
        assertThat(responses).extracting(MockHttpServletResponse::getStatus)
                .contains(201, 204, 401, 422, 400);
    }

    private MockHttpServletResponse send(String path, String body) throws Exception {
        MockHttpServletResponse response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse();
        responses.add(response);
        return response;
    }

    private void sendPut(String path, String body) throws Exception {
        responses.add(mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn()
                .getResponse());
    }

    private static String body(String field, String value) {
        return "{\"" + field + "\":\"" + value + "\"}";
    }
}
