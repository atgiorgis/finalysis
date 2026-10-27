package com.finalysis.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The vault off (docs/plans/phase-8.md §5.5), as every other test runs: the plain database is open from the start,
 * the status says PLAIN and UNLOCKED, lock is a no-op and setup or unlock are refused.
 */
@IntegrationTest
@AutoConfigureMockMvc
class VaultPlainModeTest {

    private final MockMvc mvc;
    private final VaultService vault;
    private final VaultDataSource dataSource;

    VaultPlainModeTest(MockMvc mvc, VaultService vault, VaultDataSource dataSource) {
        this.mvc = mvc;
        this.vault = vault;
        this.dataSource = dataSource;
    }

    @Test
    void openFromTheStartAndLockIsANoOp() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.vault.state").value("UNLOCKED"))
                .andExpect(jsonPath("$.vault.storage").value("PLAIN"));
        assertThat(dataSource.isOpen()).isTrue();

        mvc.perform(post("/api/vault/lock")).andExpect(status().isNoContent());

        assertThat(vault.state()).isEqualTo(VaultState.UNLOCKED);
        mvc.perform(get("/api/accounts")).andExpect(status().isOk());
        mvc.perform(post("/api/vault/setup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"a long enough password\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/vault/unlock").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"a long enough password\"}")).andExpect(status().isConflict());
    }
}
