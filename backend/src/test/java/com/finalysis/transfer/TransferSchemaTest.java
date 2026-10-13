package com.finalysis.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * V9 in plain SQL (ING-05, GR-04): link shapes, "not a transfer", rejections, and the trigger that
 * keeps {@code txn.is_transfer} equal to "on a transfer link" on every path, cascades included.
 */
@IntegrationTest
@Transactional // each test's inserts roll back
class TransferSchemaTest {

    private final JdbcTemplate jdbc;

    private long checking;
    private long checkingStatement;
    private long savings;
    private long savingsStatement;

    TransferSchemaTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void setUp() {
        checking = insertAccount("1001");
        checkingStatement = insertStatement(checking);
        savings = insertAccount("1002");
        savingsStatement = insertStatement(savings);
    }

    // Link shapes ---------------------------------------------------------------------------------

    @Test
    void rejectsLinkWithNeitherSide() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO transfer_link (method, status, confirmed_at) VALUES ('USER', 'UNMATCHED', now())"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_transfer_link_has_side");
    }

    @Test
    void acceptsOneSidedLinkOnEitherSide() {
        assertThatCode(() -> {
            insertLink(out("fp-out"), null, "USER", "UNMATCHED", true);
            insertLink(null, in("fp-in"), "USER", "UNMATCHED", true);
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsMatchedLinkWithOnlyAnOutSide() {
        long out = out("fp-out");

        assertThatThrownBy(() -> insertLink(out, null, "USER", "MATCHED", true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_transfer_link_status_sides");
    }

    @Test
    void rejectsMatchedLinkWithOnlyAnInSide() {
        long in = in("fp-in");

        assertThatThrownBy(() -> insertLink(null, in, "USER", "MATCHED", true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_transfer_link_status_sides");
    }

    @Test
    void rejectsUnmatchedLinkWithBothSides() {
        long out = out("fp-out");
        long in = in("fp-in");

        assertThatThrownBy(() -> insertLink(out, in, "USER", "UNMATCHED", true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_transfer_link_status_sides");
    }

    @Test
    void userLinkMustBeConfirmed() {
        long out = out("fp-out");
        long in = in("fp-in");

        assertThatThrownBy(() -> insertLink(out, in, "USER", "MATCHED", false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_transfer_link_user_confirmed");
    }

    @Test
    void autoLinkMayBeConfirmedOrNot() {
        assertThatCode(() -> {
            insertLink(out("fp-out-a"), in("fp-in-a"), "AUTO", "MATCHED", false);
            insertLink(out("fp-out-b"), in("fp-in-b"), "AUTO", "MATCHED", true);
        }).doesNotThrowAnyException();
    }

    // The V3 rule still holds when a side is null -------------------------------------------------

    @Test
    void oneSidedLinkCannotReuseATxnOnTheSameSide() {
        long shared = in("fp-shared");
        insertLink(out("fp-out"), shared, "AUTO", "MATCHED", false);

        assertThatThrownBy(() -> insertLink(null, shared, "USER", "UNMATCHED", true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("trg_transfer_link_txn_once");
    }

    @Test
    void oneSidedLinkCannotReuseATxnOnTheOtherSide() {
        long shared = in("fp-shared");
        insertLink(out("fp-out"), shared, "AUTO", "MATCHED", false);

        assertThatThrownBy(() -> insertLink(shared, null, "USER", "UNMATCHED", true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("trg_transfer_link_txn_once");
    }

    @Test
    void nullSidesNeverCollide() {
        long outA = out("fp-out-a");
        long inB = in("fp-in-b");
        insertLink(outA, null, "USER", "UNMATCHED", true);

        // The second link's null out side must not match the first link's null in side.
        assertThatCode(() -> insertLink(null, inB, "USER", "UNMATCHED", true)).doesNotThrowAnyException();
    }

    // is_transfer follows the links ---------------------------------------------------------------

    @Test
    void insertingALinkMarksBothSides() {
        long out = out("fp-out");
        long in = in("fp-in");
        long bystander = out("fp-bystander");

        insertLink(out, in, "AUTO", "MATCHED", false);

        assertThat(isTransfer(out)).isTrue();
        assertThat(isTransfer(in)).isTrue();
        assertThat(isTransfer(bystander)).isFalse();
    }

    @Test
    void insertingAOneSidedLinkMarksItsSide() {
        long in = in("fp-in");

        insertLink(null, in, "USER", "UNMATCHED", true);

        assertThat(isTransfer(in)).isTrue();
    }

    @Test
    void deletingALinkClearsBothSides() {
        long out = out("fp-out");
        long in = in("fp-in");
        long link = insertLink(out, in, "AUTO", "MATCHED", false);

        jdbc.update("DELETE FROM transfer_link WHERE id = ?", link);

        assertThat(isTransfer(out)).isFalse();
        assertThat(isTransfer(in)).isFalse();
    }

    @Test
    void completingAOneSidedLinkMarksTheNewSide() {
        long out = out("fp-out");
        long in = in("fp-in");
        long link = insertLink(out, null, "USER", "UNMATCHED", true);

        jdbc.update("UPDATE transfer_link SET in_txn_id = ?, status = 'MATCHED', method = 'AUTO', "
                + "confirmed_at = NULL WHERE id = ?", in, link);

        assertThat(isTransfer(out)).isTrue();
        assertThat(isTransfer(in)).isTrue();
    }

    @Test
    void movingASideClearsTheOldTxnAndMarksTheNewOne() {
        long out = out("fp-out");
        long oldIn = in("fp-old-in");
        long newIn = in("fp-new-in");
        long link = insertLink(out, oldIn, "AUTO", "MATCHED", false);

        jdbc.update("UPDATE transfer_link SET in_txn_id = ? WHERE id = ?", newIn, link);

        assertThat(isTransfer(out)).isTrue();
        assertThat(isTransfer(oldIn)).isFalse();
        assertThat(isTransfer(newIn)).isTrue();
    }

    @Test
    void deletingOneSideClearsTheCounterpart() {
        long out = out("fp-out");
        long in = in("fp-in");
        insertLink(out, in, "AUTO", "MATCHED", false);

        jdbc.update("DELETE FROM txn WHERE id = ?", in);

        assertThat(linkCount()).isZero();
        assertThat(isTransfer(out)).isFalse();
    }

    @Test
    void deletingAStatementClearsCounterpartsInOtherStatements() {
        long out = out("fp-out");
        long in = in("fp-in");
        insertLink(out, in, "USER", "MATCHED", true);

        jdbc.update("DELETE FROM statement WHERE id = ?", savingsStatement);

        assertThat(linkCount()).isZero();
        assertThat(isTransfer(out)).isFalse();
    }

    @Test
    void deletingAStatementHoldingBothSidesLeavesNoLink() {
        long otherChecking = insertAccount("1003");
        long statement = insertStatement(otherChecking);
        long out = out("fp-out");
        long in = insertTxn(otherChecking, statement, "fp-in", "250.00");
        long kept = in("fp-kept");
        insertLink(out, in, "AUTO", "MATCHED", false);
        insertLink(null, kept, "USER", "UNMATCHED", true);

        jdbc.update("DELETE FROM statement WHERE id IN (?, ?)", checkingStatement, statement);

        assertThat(linkCount()).isEqualTo(1);
        assertThat(isTransfer(kept)).isTrue();
    }

    @Test
    void isTransferAlwaysEqualsOnALink() {
        long a = out("fp-a");
        long b = in("fp-b");
        long c = out("fp-c");
        long d = in("fp-d");
        insertLink(a, b, "AUTO", "MATCHED", false);
        long oneSided = insertLink(c, null, "USER", "UNMATCHED", true);
        jdbc.update("DELETE FROM transfer_link WHERE id = ?", oneSided);
        insertLink(null, d, "USER", "UNMATCHED", true);
        jdbc.update("DELETE FROM txn WHERE id = ?", a);

        Integer mismatches = jdbc.queryForObject("""
                SELECT count(*) FROM txn t
                WHERE t.is_transfer <> EXISTS (
                    SELECT 1 FROM transfer_link l WHERE l.out_txn_id = t.id OR l.in_txn_id = t.id)
                """, Integer.class);
        assertThat(mismatches).isZero();
    }

    // Not a transfer ------------------------------------------------------------------------------

    @Test
    void newTxnIsNotExcluded() {
        Boolean excluded = jdbc.queryForObject(
                "SELECT transfer_excluded FROM txn WHERE id = ?", Boolean.class, out("fp-out"));

        assertThat(excluded).isFalse();
    }

    @Test
    void excludedTxnCannotBeLinked() {
        long out = out("fp-out");
        long in = in("fp-in");
        jdbc.update("UPDATE txn SET transfer_excluded = true WHERE id = ?", out);

        assertThatThrownBy(() -> insertLink(out, in, "AUTO", "MATCHED", false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_txn_transfer_excluded");
    }

    @Test
    void linkedTxnCannotBeExcluded() {
        long out = out("fp-out");
        insertLink(out, in("fp-in"), "AUTO", "MATCHED", false);

        assertThatThrownBy(() -> jdbc.update("UPDATE txn SET transfer_excluded = true WHERE id = ?", out))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_txn_transfer_excluded");
    }

    // Rejections ----------------------------------------------------------------------------------

    @Test
    void rejectionIsUniquePerPair() {
        long out = out("fp-out");
        long in = in("fp-in");
        insertRejection(out, in);

        assertThatThrownBy(() -> insertRejection(out, in))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("pk_transfer_rejection");
    }

    @Test
    void rejectionOfATxnWithItselfIsRefused() {
        long out = out("fp-out");

        assertThatThrownBy(() -> insertRejection(out, out))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_transfer_rejection_distinct");
    }

    @Test
    void rejectionGoesWithEitherTxn() {
        long out = out("fp-out");
        long in = in("fp-in");
        long otherOut = out("fp-other-out");
        long otherIn = in("fp-other-in");
        insertRejection(out, in);
        insertRejection(otherOut, otherIn);

        jdbc.update("DELETE FROM txn WHERE id = ?", out);
        jdbc.update("DELETE FROM txn WHERE id = ?", otherIn);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_rejection WHERE out_txn_id IN (?, ?)",
                Integer.class, out, otherOut)).isZero();
    }

    @Test
    void rejectionGoesWithItsStatement() {
        insertRejection(out("fp-out"), in("fp-in"));

        jdbc.update("DELETE FROM statement WHERE id = ?", checkingStatement);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_rejection WHERE in_txn_id IN "
                + "(SELECT id FROM txn WHERE statement_id = ?)", Integer.class, savingsStatement)).isZero();
    }

    // Helpers -------------------------------------------------------------------------------------

    private long out(String fingerprint) {
        return insertTxn(checking, checkingStatement, fingerprint, "-250.00");
    }

    private long in(String fingerprint) {
        return insertTxn(savings, savingsStatement, fingerprint, "250.00");
    }

    private long insertLink(Long out, Long in, String method, String status, boolean confirmed) {
        return jdbc.queryForObject("""
                INSERT INTO transfer_link (out_txn_id, in_txn_id, method, status, confirmed_at)
                VALUES (?, ?, ?, ?, CASE WHEN ? THEN now() END) RETURNING id
                """, Long.class, out, in, method, status, confirmed);
    }

    private void insertRejection(long out, long in) {
        jdbc.update("INSERT INTO transfer_rejection (out_txn_id, in_txn_id) VALUES (?, ?)", out, in);
    }

    private boolean isTransfer(long txn) {
        return jdbc.queryForObject("SELECT is_transfer FROM txn WHERE id = ?", Boolean.class, txn);
    }

    private int linkCount() {
        return jdbc.queryForObject("SELECT count(*) FROM transfer_link WHERE out_txn_id IN "
                + "(SELECT id FROM txn WHERE account_id IN (?, ?)) OR in_txn_id IN "
                + "(SELECT id FROM txn WHERE account_id IN (?, ?))", Integer.class, checking, savings, checking, savings);
    }

    private long insertAccount(String lastFour) {
        return jdbc.queryForObject("""
                INSERT INTO account (name, institution, type, last_four)
                VALUES ('Test', 'Northfield Bank', 'CHECKING', ?) RETURNING id
                """, Long.class, lastFour);
    }

    private long insertStatement(long accountId) {
        return jdbc.queryForObject("""
                INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash, parser)
                VALUES (?, DATE '2026-04-01', DATE '2026-04-30', 'test.csv', ?, 'fixture') RETURNING id
                """, Long.class, accountId, "hash-" + accountId + "-" + System.nanoTime());
    }

    private long insertTxn(long accountId, long statementId, String fingerprint, String amount) {
        return jdbc.queryForObject("""
                INSERT INTO txn (account_id, statement_id, txn_date, raw_description, amount,
                                 source_section, fingerprint)
                VALUES (?, ?, DATE '2026-04-02', 'TEST', ?::numeric, 'WITHDRAWAL', ?) RETURNING id
                """, Long.class, accountId, statementId, amount, fingerprint);
    }
}
