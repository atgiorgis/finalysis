package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.account.Account;
import com.finalysis.account.AccountRepository;
import com.finalysis.support.EntityFixtures;
import com.finalysis.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * V8 {@code unparsed_upload} (ING-13): its checks, at most one open upload per account and file,
 * and a resolving statement's deletion. A failed statement aborts the transaction, so each
 * violation is the last statement of its test.
 */
@IntegrationTest
@Transactional // each test's inserts roll back
class UnparsedUploadRepositoryTest {

    private static final String SHA = "a".repeat(64);
    private static final String INSERT = "INSERT INTO unparsed_upload (account_id, file_sha256, file_name, reason) "
            + "VALUES (?, ?, ?, ?)";

    private final UnparsedUploadRepository uploads;
    private final StatementRepository statements;
    private final AccountRepository accounts;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    UnparsedUploadRepositoryTest(UnparsedUploadRepository uploads, StatementRepository statements,
                                 AccountRepository accounts, EntityManager entityManager, JdbcTemplate jdbc) {
        this.uploads = uploads;
        this.statements = statements;
        this.accounts = accounts;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    @Test
    void savesAndLoadsWithColumnDefaults() {
        Account account = accounts.save(EntityFixtures.account("1001"));
        Long id = uploads.saveAndFlush(new UnparsedUpload(account, SHA, "april.pdf", UnparsedReason.NO_TEXT_LAYER))
                .getId();
        entityManager.clear();

        UnparsedUpload loaded = uploads.findById(id).orElseThrow();

        assertThat(loaded.getAccount().getId()).isEqualTo(account.getId());
        assertThat(loaded.getFileSha256()).isEqualTo(SHA);
        assertThat(loaded.getFileName()).isEqualTo("april.pdf");
        assertThat(loaded.getReason()).isEqualTo(UnparsedReason.NO_TEXT_LAYER);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getResolvedAt()).isNull();
        assertThat(loaded.getResolvedStatementId()).isNull();
        assertThat(loaded.toString()).doesNotContain("april").contains("NO_TEXT_LAYER").contains(SHA.substring(0, 12))
                .doesNotContain(SHA);
    }

    @Test
    void findOpenAndResolveOpenSeeOnlyTheAccountsOpenUpload() {
        Account account = accounts.save(EntityFixtures.account("1001"));
        Account other = accounts.save(EntityFixtures.account("1003"));
        Statement statement = statements.saveAndFlush(EntityFixtures.statement(account));
        UnparsedUpload open = uploads.saveAndFlush(new UnparsedUpload(account, SHA, "a.pdf",
                UnparsedReason.UNRECOGNIZED_LAYOUT));
        uploads.saveAndFlush(new UnparsedUpload(other, SHA, "a.pdf", UnparsedReason.UNRECOGNIZED_LAYOUT));

        assertThat(uploads.findOpen(account.getId(), SHA)).map(UnparsedUpload::getId).contains(open.getId());
        assertThat(uploads.findOpen(account.getId(), "b".repeat(64))).isEmpty();
        assertThat(uploads.resolveOpen(account.getId(), SHA, statement.getId())).isEqualTo(1);
        entityManager.clear();

        assertThat(uploads.findOpen(account.getId(), SHA)).isEmpty();
        assertThat(uploads.findOpen(other.getId(), SHA)).isPresent();
        UnparsedUpload resolved = uploads.findById(open.getId()).orElseThrow();
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(resolved.getResolvedStatementId()).isEqualTo(statement.getId());
        assertThat(uploads.resolveOpen(account.getId(), SHA, statement.getId())).isZero();
    }

    @Test
    void aResolvedUploadAndANewOpenOneForTheSameFileCoexist() {
        Account account = accounts.save(EntityFixtures.account("1001"));
        jdbc.update(INSERT, account.getId(), SHA, "a.pdf", "UNRECOGNIZED_LAYOUT");
        jdbc.update("UPDATE unparsed_upload SET resolved_at = now() WHERE account_id = ?", account.getId());

        jdbc.update(INSERT, account.getId(), SHA, "a.pdf", "UNRECOGNIZED_LAYOUT");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM unparsed_upload WHERE account_id = ?", Integer.class,
                account.getId())).isEqualTo(2);
    }

    @Test
    void atMostOneOpenUploadPerAccountAndFile() {
        Account account = accounts.save(EntityFixtures.account("1001"));
        jdbc.update(INSERT, account.getId(), SHA, "a.pdf", "UNRECOGNIZED_LAYOUT");

        assertThatThrownBy(() -> jdbc.update(INSERT, account.getId(), SHA, "b.pdf", "NO_TEXT_LAYER"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_unparsed_upload_open");
    }

    @Test
    void deletingTheResolvingStatementKeepsTheUploadResolved() {
        Account account = accounts.save(EntityFixtures.account("1001"));
        Statement statement = statements.saveAndFlush(EntityFixtures.statement(account));
        jdbc.update(INSERT, account.getId(), SHA, "a.pdf", "UNRECOGNIZED_LAYOUT");
        uploads.resolveOpen(account.getId(), SHA, statement.getId());

        jdbc.update("DELETE FROM statement WHERE id = ?", statement.getId());

        assertThat(jdbc.queryForMap("SELECT resolved_at, resolved_statement_id FROM unparsed_upload WHERE account_id = ?",
                account.getId())).containsEntry("resolved_statement_id", null).extractingByKey("resolved_at").isNotNull();
    }

    @Test
    void theHashMustBeLowercaseHex() {
        long account = accounts.save(EntityFixtures.account("1001")).getId();

        assertThatThrownBy(() -> jdbc.update(INSERT, account, "A".repeat(64), "a.pdf", "UNRECOGNIZED_LAYOUT"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_unparsed_upload_file_sha256");
    }

    @Test
    void theReasonMustBeKnown() {
        long account = accounts.save(EntityFixtures.account("1001")).getId();

        assertThatThrownBy(() -> jdbc.update(INSERT, account, SHA, "a.pdf", "ENCRYPTED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_unparsed_upload_reason");
    }

    @Test
    void theFileNameMustNotBeEmpty() {
        long account = accounts.save(EntityFixtures.account("1001")).getId();

        assertThatThrownBy(() -> jdbc.update(INSERT, account, SHA, "", "UNRECOGNIZED_LAYOUT"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_unparsed_upload_file_name_length");
    }

    @Test
    void aResolvingStatementRequiresResolvedAt() {
        Account account = accounts.save(EntityFixtures.account("1001"));
        Statement statement = statements.saveAndFlush(EntityFixtures.statement(account));
        jdbc.update(INSERT, account.getId(), SHA, "a.pdf", "UNRECOGNIZED_LAYOUT");

        assertThatThrownBy(() -> jdbc.update("UPDATE unparsed_upload SET resolved_statement_id = ? WHERE account_id = ?",
                statement.getId(), account.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_unparsed_upload_resolution");
    }
}
