package com.finalysis.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Localhost hardening (docs/plans/phase-8.md §9.5, §16 decision 9), with the vault off as every other suite runs: a
 * foreign Host gets 421 (DNS rebinding), a write from a foreign Origin 403; the app's own origins, and tools that send
 * none, pass. Phone-only and computer-only endpoints answer on the wrong side with their own refusal.
 */
@IntegrationTest
@AutoConfigureMockMvc
class HostOriginFilterTest {

    private final MockMvc mvc;

    HostOriginFilterTest(MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void aForeignHostIsMisdirected() throws Exception {
        mvc.perform(get("/api/status").header("Host", "attacker.example")).andExpect(status().is(421))
                .andExpect(jsonPath("$.type").value("urn:finalysis:problem:host-refused"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/status").header("Host", "127.0.0.1:8080")).andExpect(status().isOk());
        mvc.perform(get("/api/status").header("Host", "localhost:8080")).andExpect(status().isOk());
        mvc.perform(get("/api/status").header("Host", "[::1]:8080")).andExpect(status().isOk());
        mvc.perform(get("/api/status").header("Host", "192.168.1.157:8080")).andExpect(status().is(421));
    }

    @Test
    void aWriteFromAForeignOriginIsForbiddenAReadIsNot() throws Exception {
        mvc.perform(post("/api/vault/lock").header("Origin", "https://attacker.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:finalysis:problem:origin-refused"));
        mvc.perform(post("/api/vault/lock").header("Origin", "http://localhost:5174")).andExpect(status().isForbidden());
        mvc.perform(post("/api/vault/lock").header("Origin", "null")).andExpect(status().isForbidden());

        mvc.perform(post("/api/vault/lock").header("Origin", "http://localhost:5173")).andExpect(status().isNoContent());
        mvc.perform(post("/api/vault/lock").header("Origin", "http://127.0.0.1:5173")).andExpect(status().isNoContent());
        mvc.perform(post("/api/vault/lock")).andExpect(status().isNoContent());
        mvc.perform(get("/api/status").header("Origin", "https://attacker.example")).andExpect(status().isOk());
    }

    @Test
    void loginIsForThePhoneAndTheSettingForTheComputer() throws Exception {
        mvc.perform(post("/api/access/login").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:finalysis:problem:lan-only"));
        mvc.perform(get("/api/access/session")).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true)).andExpect(jsonPath("$.lan").value(false));
    }

    @Test
    void localhostHasNoSessionCookie() throws Exception {
        mvc.perform(get("/api/accounts")).andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(put("/api/access").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
