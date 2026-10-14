package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import com.finalysis.transfer.TransferLock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code DELETE /api/statements/{id}} (ING-07, docs/plans/phase-3c.md §4.4): cascades and counts,
 * counterparts losing {@code is_transfer}, detection re-running after commit, the file importable
 * again, and the account lock and then the transfer lock.
 *
 * <p>Imports and deletes commit for real, so the after-commit detection runs as in production.
 * Detection covers every account, so each test starts from a database without txns.
 */
@IntegrationTest
@AutoConfigureMockMvc
class StatementDeleteTest {

    private static final String CHECKING_OFX = "ofx/northfield-checking_2026-04.ofx";
    private static final String CHECKING_CSV = "statements/northfield-checking_2026-04.csv";
    private static final String APEX_QFX = "ofx/apex-visa_2026-04.qfx";
    private static final String CARD_PAYMENT = "842.17"; // checking 04-09 → Apex 04-12, in both files

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final ImportTestSupport support;
    private final TransactionTemplate transaction;
    private final TransferLock transferLock;

    StatementDeleteTest(MockMvc mockMvc, JsonMapper jsonMapper, AccountRepository accounts, JdbcTemplate jdbc,
                        TransactionTemplate transaction, TransferLock transferLock) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.transaction = transaction;
        this.transferLock = transferLock;
    }

    @BeforeEach
    void databaseHasNoTxns() {
        assertThat(support.count("SELECT count(*) FROM txn"))
                .as("detection covers every account, so another test's leftover rows would change its result")
                .isZero();
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @Test
    void deletesTheStatementWithItsRowsAndTheirLinks() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long card = support.account(AccountType.CREDIT_CARD, "2001");
        long statement = importOk(checking, CHECKING_OFX);
        importOk(card, APEX_QFX);
        long payment = txn(checking, "-" + CARD_PAYMENT);
        long cardPayment = txn(card, CARD_PAYMENT);
        long link = jdbc.queryForObject("SELECT id FROM transfer_link WHERE out_txn_id = ?", Long.class, payment);
        mockMvc.perform(post("/api/transfers/{id}/confirm", link)).andExpect(status().isOk());
        long toSavings = txn(checking, "-500.00"); // savings isn't in the app
        createTransfer("{\"outTxnId\": " + toSavings + "}");
        int rows = support.count("SELECT count(*) FROM txn WHERE statement_id = ?", statement);

        mockMvc.perform(delete("/api/statements/{id}", statement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statementId").value(statement))
                .andExpect(jsonPath("$.transactionsDeleted").value(rows))
                .andExpect(jsonPath("$.transferLinksRemoved").value(2)); // the confirmed one and the one-sided one

        mockMvc.perform(get("/api/statements/{id}", statement)).andExpect(status().isNotFound());
        assertThat(support.count("SELECT count(*) FROM txn WHERE account_id = ?", checking)).isZero();
        assertThat(support.count("SELECT count(*) FROM transfer_link")).isZero();
        assertThat(isTransfer(cardPayment)).isFalse();
        assertThat(support.count("SELECT count(*) FROM txn WHERE account_id = ?", card)).isPositive();
    }

    /** The file hash went with the statement; the re-import's detection links the card payment again, unconfirmed. */
    @Test
    void theSameFileCanBeImportedAgain() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long card = support.account(AccountType.CREDIT_CARD, "2001");
        long statement = importOk(checking, CHECKING_OFX);
        importOk(card, APEX_QFX);
        long cardPayment = txn(card, CARD_PAYMENT);
        mockMvc.perform(post("/api/transfers/{id}/confirm", linkOf(cardPayment))).andExpect(status().isOk());

        mockMvc.perform(delete("/api/statements/{id}", statement)).andExpect(status().isOk());
        importOk(checking, CHECKING_OFX);

        assertThat(isTransfer(cardPayment)).isTrue();
        assertThat(jdbc.queryForMap("SELECT method, confirmed_at FROM transfer_link WHERE in_txn_id = ?", cardPayment))
                .containsEntry("method", "AUTO").containsEntry("confirmed_at", null);
    }

    /**
     * The duplicate-account story (§7): the checking statement imported into two accounts gives the card
     * payment two out candidates, so it waits for review. Deleting the wrong copy re-runs detection after
     * commit, which links the right one.
     */
    @Test
    void detectionRunsAgainAfterTheDelete() throws Exception {
        long right = support.account(AccountType.CHECKING, "1001");
        // Same type and last four, so the file is accepted; another institution, since V12 allows one per bank.
        long wrong = support.account(AccountType.CHECKING, "1001", "Test 1001", "Other Bank");
        long card = support.account(AccountType.CREDIT_CARD, "2001");
        saveSuggestedMapping(right, CHECKING_CSV);
        importOk(right, CHECKING_CSV, Map.of("periodStart", "2026-04-01", "periodEnd", "2026-04-30"));
        long wrongStatement = importOk(wrong, CHECKING_OFX);
        importOk(card, APEX_QFX);
        long cardPayment = txn(card, CARD_PAYMENT);
        assertThat(isTransfer(cardPayment)).as("two candidates: left for review").isFalse();

        mockMvc.perform(delete("/api/statements/{id}", wrongStatement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferLinksRemoved").value(0));

        assertThat(isTransfer(cardPayment)).isTrue();
        assertThat(jdbc.queryForObject("SELECT out_txn_id FROM transfer_link WHERE in_txn_id = ?", Long.class,
                cardPayment)).isEqualTo(txn(right, "-" + CARD_PAYMENT));
    }

    @Test
    void rejectionsOnItsRowsGoToo() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long card = support.account(AccountType.CREDIT_CARD, "2001");
        importOk(checking, CHECKING_OFX);
        long cardStatement = importOk(card, APEX_QFX);
        long payment = txn(checking, "-" + CARD_PAYMENT);
        mockMvc.perform(delete("/api/transfers/{id}", linkOf(payment))).andExpect(status().isNoContent());
        assertThat(support.count("SELECT count(*) FROM transfer_rejection WHERE out_txn_id = ?", payment)).isOne();

        mockMvc.perform(delete("/api/statements/{id}", cardStatement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferLinksRemoved").value(0));

        assertThat(support.count("SELECT count(*) FROM transfer_rejection WHERE out_txn_id = ?", payment)).isZero();
        assertThat(isTransfer(payment)).isFalse();
    }

    /** Any status: here a NEEDS_REVIEW statement, which also leaves the review queue with it. */
    @Test
    void aStatementOfAnyStatusCanBeDeleted() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        importOk(checking, CHECKING_OFX);
        JsonNode may = json(support.upload(checking, "may.ofx",
                ImportTestSupport.sample("edge-cases/ofx-ledgerbal-mismatch.ofx")).andExpect(status().isCreated()));
        assertThat(may.get("status").asString()).isEqualTo("NEEDS_REVIEW");

        mockMvc.perform(delete("/api/statements/{id}", may.get("statementId").asLong())).andExpect(status().isOk());

        mockMvc.perform(get("/api/statements/{id}", may.get("statementId").asLong())).andExpect(status().isNotFound());
    }

    @Test
    void anUnknownStatementIsNotFound() throws Exception {
        mockMvc.perform(delete("/api/statements/{id}", Long.MAX_VALUE)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/statements/{id}", "abc")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ locks (§2.5, §4.4)

    @Test
    void theDeleteWaitsForTheAccountLock() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long statement = importOk(checking, CHECKING_OFX);

        assertWaitsWhileHeld(statement, status ->
                jdbc.queryForList("SELECT id FROM account WHERE id = ? FOR UPDATE", checking));
    }

    /** The cascade removes links, a transfer write: it waits for detection and the transfer actions. */
    @Test
    void theDeleteWaitsForTheTransferLock() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long statement = importOk(checking, CHECKING_OFX);

        assertWaitsWhileHeld(statement, status -> transferLock.acquire());
    }

    /** Holds a lock in another transaction, starts the delete, and checks it waits with nothing deleted. */
    private void assertWaitsWhileHeld(long statement, Consumer<Object> takeLock) throws Exception {
        int rows = support.count("SELECT count(*) FROM txn WHERE statement_id = ?", statement);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            takeLock.accept(status);
            held.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<MvcResult> deleting = CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(delete("/api/statements/{id}", statement)).andReturn();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        awaitLockWaiter();
        assertThat(deleting).isNotDone();
        assertThat(support.count("SELECT count(*) FROM txn WHERE statement_id = ?", statement)).isEqualTo(rows);

        release.countDown();
        holder.get(30, TimeUnit.SECONDS);
        assertThat(deleting.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        assertThat(support.count("SELECT count(*) FROM statement WHERE id = ?", statement)).isZero();
    }

    private void awaitLockWaiter() {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (support.count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                + "AND datname = current_database()") < 1) {
            assertThat(System.nanoTime()).as("the delete waiting on the lock").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    // ------------------------------------------------------------------ helpers

    private long importOk(long accountId, String sampleFile) throws Exception {
        return importOk(accountId, sampleFile, Map.of());
    }

    private long importOk(long accountId, String sampleFile, Map<String, String> params) throws Exception {
        String fileName = sampleFile.substring(sampleFile.lastIndexOf('/') + 1);
        return json(support.upload(accountId, fileName, ImportTestSupport.sample(sampleFile), params)
                .andExpect(status().isCreated())).get("statementId").asLong();
    }

    private void saveSuggestedMapping(long accountId, String sampleFile) throws Exception {
        String fileName = sampleFile.substring(sampleFile.lastIndexOf('/') + 1);
        JsonNode preview = json(support.preview(accountId, fileName, ImportTestSupport.sample(sampleFile))
                .andExpect(status().isOk()));
        support.putMapping(accountId, preview.get("suggestedMapping").get("mapping").toString())
                .andExpect(status().isCreated());
    }

    private void createTransfer(String body) throws Exception {
        mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    /** The account's one txn of this amount. */
    private long txn(long accountId, String amount) {
        return jdbc.queryForObject("SELECT id FROM txn WHERE account_id = ? AND amount = ?::numeric", Long.class,
                accountId, amount);
    }

    private long linkOf(long txnId) {
        return jdbc.queryForObject("SELECT id FROM transfer_link WHERE out_txn_id = ? OR in_txn_id = ?", Long.class,
                txnId, txnId);
    }

    private boolean isTransfer(long txnId) {
        return jdbc.queryForObject("SELECT is_transfer FROM txn WHERE id = ?", Boolean.class, txnId);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
