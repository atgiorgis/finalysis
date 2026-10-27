package com.finalysis.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.vault.VaultFakes.FakeDatabaseRuntime;
import com.finalysis.vault.VaultFakes.FakeVaultMount;
import com.finalysis.vault.VaultFakes.VaultEvents;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kept statement files (docs/plans/phase-8.md §7, §10 "Statement files"): after an import, the same file sent again is
 * kept in the vault's statements folder, named by id and hash only, owner-only; another file is refused; the copy goes
 * when its statement is deleted; nothing is kept while locked.
 */
@VaultEnabledTest
class KeptStatementFileTest {

    private static final String PASSWORD = "kept files test password";
    private static final String OFX = "ofx/northfield-checking_2026-04.ofx";
    private static final String CLIENT_NAME = "acct-0047391001 April statement.ofx";

    private final MockMvc mvc;
    private final JsonMapper json;
    private final VaultService vault;
    private final VaultProperties properties;
    private final FakeVaultMount mount;
    private final FakeDatabaseRuntime runtime;
    private final VaultEvents events;
    private final AccountRepository accounts;
    private final JdbcTemplate jdbc;
    private ImportTestSupport support;

    KeptStatementFileTest(MockMvc mvc, JsonMapper json, VaultService vault, VaultProperties properties,
                          FakeVaultMount mount, FakeDatabaseRuntime runtime, VaultEvents events,
                          AccountRepository accounts, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.json = json;
        this.vault = vault;
        this.properties = properties;
        this.mount = mount;
        this.runtime = runtime;
        this.events = events;
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void unlockedVault() {
        VaultTestState.reset(vault, properties, mount, runtime, events);
        vault.setup(PASSWORD);
        support = new ImportTestSupport(mvc, accounts, jdbc);
    }

    @AfterEach
    void cleanUp() {
        if (vault.state() != VaultState.UNLOCKED) {
            vault.unlock(PASSWORD);
        }
        support.deleteCreatedAccounts();
        VaultTestSupport.deleteRecursively(statementsDir());
    }

    @Test
    void theSameFileIsKeptByIdAndHashOnlyOwnerOnlyAndGoesWithItsStatement() throws Exception {
        byte[] file = ImportTestSupport.sample(OFX);
        long statementId = importIt(file);
        assertThat(kept(statementId)).isFalse();

        MockHttpServletResponse response = keep(statementId, file);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(kept(statementId)).isTrue();
        List<Path> copies = copies();
        assertThat(copies).hasSize(1);
        Path copy = copies.getFirst();
        assertThat(copy.getFileName().toString()).matches(statementId + "-[0-9a-f]{12}\\.ofx")
                .doesNotContain("0047391001").doesNotContain("April");
        assertThat(Files.readAllBytes(copy)).isEqualTo(file);
        assertThat(Files.getPosixFilePermissions(copy))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

        // Again: nothing changes.
        assertThat(keep(statementId, file).getStatus()).isEqualTo(200);
        assertThat(copies()).hasSize(1);

        assertThat(mvc.perform(delete("/api/statements/{id}", statementId)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(copies()).isEmpty();
    }

    @Test
    void anotherFileIsRefusedAndNothingIsStored() throws Exception {
        long statementId = importIt(ImportTestSupport.sample(OFX));
        byte[] other = ImportTestSupport.sample("ofx/northfield-checking_2026-05.ofx");

        MockHttpServletResponse response = keep(statementId, other);

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(json.readTree(response.getContentAsString()).get("type").asString())
                .isEqualTo("urn:finalysis:problem:file-mismatch");
        assertThat(response.getContentAsString()).doesNotContain(CLIENT_NAME).doesNotContain("0047391001");
        assertThat(copies()).isEmpty();
    }

    @Test
    void anUnknownStatementIs404() throws Exception {
        assertThat(keep(987654321L, ImportTestSupport.sample(OFX)).getStatus()).isEqualTo(404);
    }

    @Test
    void whileLockedNothingIsKeptOrRead() throws Exception {
        byte[] file = ImportTestSupport.sample(OFX);
        long statementId = importIt(file);
        vault.lock("test");

        assertThat(keep(statementId, file).getStatus()).isEqualTo(423);
        assertThat(mvc.perform(get("/api/statements/{id}/file", statementId)).andReturn().getResponse().getStatus())
                .isEqualTo(423);
        assertThat(copies()).isEmpty();
    }

    private long importIt(byte[] file) throws Exception {
        long account = support.account(AccountType.CHECKING, "1001");
        MockHttpServletResponse response = support.upload(account, CLIENT_NAME, file).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return json.readTree(response.getContentAsString()).get("statementId").asLong();
    }

    private MockHttpServletResponse keep(long statementId, byte[] bytes) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/statements/{id}/file", statementId);
        request.with(r -> {
            r.setMethod("PUT");
            return r;
        });
        request.file(new MockMultipartFile("file", CLIENT_NAME, "application/octet-stream", bytes));
        return mvc.perform(request).andReturn().getResponse();
    }

    private boolean kept(long statementId) throws Exception {
        String body = mvc.perform(get("/api/statements/{id}/file", statementId)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).get("fileKept").asBoolean();
    }

    private Path statementsDir() {
        return properties.mountPoint().resolve("statements");
    }

    private List<Path> copies() throws Exception {
        if (!Files.isDirectory(statementsDir())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(statementsDir())) {
            return files.toList();
        }
    }
}
