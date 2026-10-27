package com.finalysis.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.finalysis.vault.VaultFakes.FakeDatabaseRuntime;
import com.finalysis.vault.VaultFakes.FakeVaultMount;
import com.finalysis.vault.VaultFakes.VaultEvents;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The locked-mode gate (docs/plans/phase-8.md §5.1, §10 "Locked gate"): with the vault locked, **every** handler
 * mapping, read from {@link RequestMappingHandlerMapping} so a new endpoint is covered without editing this test,
 * answers 423 with the {@code vault-locked} problem and no detail, except the allowlist. The data source refuses a
 * connection too.
 */
@VaultEnabledTest
class VaultGateTest {

    private static final String PASSWORD = "gate test password, synthetic";

    private final MockMvc mvc;
    private final RequestMappingHandlerMapping mappings;
    private final VaultService vault;
    private final VaultDataSource dataSource;
    private final VaultProperties properties;
    private final FakeVaultMount mount;
    private final FakeDatabaseRuntime runtime;
    private final VaultEvents events;

    VaultGateTest(MockMvc mvc, RequestMappingHandlerMapping requestMappingHandlerMapping, VaultService vault,
                  VaultDataSource dataSource, VaultProperties properties, FakeVaultMount mount,
                  FakeDatabaseRuntime runtime, VaultEvents events) {
        this.mvc = mvc;
        this.mappings = requestMappingHandlerMapping;
        this.vault = vault;
        this.dataSource = dataSource;
        this.properties = properties;
        this.mount = mount;
        this.runtime = runtime;
        this.events = events;
    }

    @BeforeEach
    void lockedVault() {
        VaultTestState.reset(vault, properties, mount, runtime, events);
        vault.setup(PASSWORD);
        vault.lock("test");
        assertThat(vault.state()).isEqualTo(VaultState.LOCKED);
    }

    @Test
    void everyMappingButTheAllowlistAnswers423WhileLocked() throws Exception {
        List<String> checked = new ArrayList<>();
        Set<String> allowedSeen = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    String url = pattern.replaceAll("\\{\\*?[^}]+}", "1");
                    if (VaultGate.allowed(method.name(), pattern)) {
                        allowedSeen.add(method.name() + " " + pattern);
                        continue;
                    }
                    MvcResult result = mvc.perform(request(HttpMethod.valueOf(method.name()), url)
                            .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
                    assertThat(result.getResponse().getStatus()).as(method + " " + pattern).isEqualTo(423);
                    assertThat(result.getResponse().getContentAsString()).as(method + " " + pattern)
                            .contains("urn:finalysis:problem:vault-locked").doesNotContain("detail");
                    checked.add(method + " " + pattern);
                }
            }
        }
        assertThat(checked).hasSizeGreaterThan(40).contains("GET /api/accounts", "POST /api/vault/password",
                "POST /api/vault/activity");
        // Every allowlisted endpoint that is a controller mapping was found (health is actuator's).
        assertThat(allowedSeen).containsExactlyInAnyOrder("GET /api/status", "POST /api/vault/setup",
                "POST /api/vault/unlock", "POST /api/vault/recover", "POST /api/vault/lock");
    }

    @Test
    void unknownPathsAreRefusedToo() throws Exception {
        assertThat(mvc.perform(get("/api/no-such-thing")).andReturn().getResponse().getStatus()).isEqualTo(423);
        assertThat(mvc.perform(get("/")).andReturn().getResponse().getStatus()).isEqualTo(423);
    }

    @Test
    void theAllowlistAnswers() throws Exception {
        assertThat(mvc.perform(get("/api/status")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(post("/api/vault/lock")).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(mvc.perform(post("/api/vault/setup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"another long password\"}")).andReturn().getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void theDataSourceRefusesConnectionsWhileLocked() {
        assertThat(dataSource.isOpen()).isFalse();
        assertThatThrownBy(dataSource::getConnection).isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo(VaultDataSource.LOCKED_SQL_STATE);
    }

    @Test
    void notSetUpIsGatedTheSameWay() throws Exception {
        VaultTestState.reset(vault, properties, mount, runtime, events);
        assertThat(vault.state()).isEqualTo(VaultState.NOT_SET_UP);
        assertThat(mvc.perform(get("/api/accounts")).andReturn().getResponse().getStatus()).isEqualTo(423);
    }
}
