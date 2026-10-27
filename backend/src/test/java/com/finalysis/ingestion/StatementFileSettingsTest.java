package com.finalysis.ingestion;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The "after import" default (docs/plans/phase-8.md §7) and, with the vault off, keeping a copy refused: there is no
 * encrypted volume to keep it in.
 */
@IntegrationTest
@AutoConfigureMockMvc
class StatementFileSettingsTest {

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ImportTestSupport support;

    StatementFileSettingsTest(MockMvc mvc, JdbcTemplate jdbc, JsonMapper json, AccountRepository accounts) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.json = json;
        this.support = new ImportTestSupport(mvc, accounts, jdbc);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM app_setting WHERE key = ?", StatementFileSettings.KEY);
        support.deleteCreatedAccounts();
    }

    @Test
    void askByDefaultThenWhatWasSaved() throws Exception {
        mvc.perform(get("/api/imports/settings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.afterImport").value("ASK"));

        for (String choice : new String[] {"KEEP", "DELETE", "BOTH", "LEAVE", "ASK"}) {
            mvc.perform(put("/api/imports/settings").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"afterImport\":\"" + choice + "\"}")).andExpect(status().isNoContent());
            mvc.perform(get("/api/imports/settings")).andExpect(jsonPath("$.afterImport").value(choice));
        }
    }

    @Test
    void anUnknownChoiceIs400() throws Exception {
        mvc.perform(put("/api/imports/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"afterImport\":\"SHRED\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("afterImport"));
        mvc.perform(put("/api/imports/settings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withTheVaultOffACopyIsRefusedAndNothingIsSaid() throws Exception {
        byte[] file = ImportTestSupport.sample("ofx/northfield-checking_2026-04.ofx");
        long account = support.account(AccountType.CHECKING, "1001");
        String body = support.upload(account, "april.ofx", file).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        long statementId = json.readTree(body).get("statementId").asLong();

        mvc.perform(multipart("/api/statements/{id}/file", statementId)
                        .file(new MockMultipartFile("file", "april.ofx", "application/octet-stream", file))
                        .with(r -> {
                            r.setMethod("PUT");
                            return r;
                        }))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:finalysis:problem:no-vault"));
        mvc.perform(get("/api/statements/{id}/file", statementId)).andExpect(jsonPath("$.fileKept").value(false));
    }
}
