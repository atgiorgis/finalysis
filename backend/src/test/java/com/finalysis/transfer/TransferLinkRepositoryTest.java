package com.finalysis.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.account.Account;
import com.finalysis.account.AccountRepository;
import com.finalysis.ingestion.Statement;
import com.finalysis.ingestion.StatementRepository;
import com.finalysis.ingestion.Txn;
import com.finalysis.ingestion.TxnRepository;
import com.finalysis.support.EntityFixtures;
import com.finalysis.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/** The entity mapping and the link states of phase-3c.md §1.2; raw constraints are in TransferSchemaTest. */
@IntegrationTest
@Transactional // each test's inserts roll back
class TransferLinkRepositoryTest {

    private static final OffsetDateTime CONFIRMED = OffsetDateTime.of(2026, 10, 13, 9, 0, 0, 0, ZoneOffset.UTC);

    private final TransferLinkRepository links;
    private final TxnRepository txns;
    private final StatementRepository statements;
    private final AccountRepository accounts;
    private final EntityManager entityManager;

    private Account checking;
    private Statement checkingStatement;
    private Account savings;
    private Statement savingsStatement;

    TransferLinkRepositoryTest(TransferLinkRepository links, TxnRepository txns, StatementRepository statements,
                               AccountRepository accounts, EntityManager entityManager) {
        this.links = links;
        this.txns = txns;
        this.statements = statements;
        this.accounts = accounts;
        this.entityManager = entityManager;
    }

    @BeforeEach
    void setUp() {
        checking = accounts.save(EntityFixtures.account("1001"));
        checkingStatement = statements.save(EntityFixtures.statement(checking));
        savings = accounts.save(EntityFixtures.account("1002"));
        savingsStatement = statements.save(EntityFixtures.statement(savings));
    }

    @Test
    void savesAndLoadsDetectedLink() {
        Txn out = out("fp-out");
        Txn in = in("fp-in");
        Long id = links.saveAndFlush(TransferLink.detected(out, in)).getId();
        entityManager.clear(); // load from the database, not the persistence context

        TransferLink loaded = links.findById(id).orElseThrow();

        assertThat(loaded.getOutTxn().getId()).isEqualTo(out.getId());
        assertThat(loaded.getInTxn().getId()).isEqualTo(in.getId());
        assertThat(loaded.getMethod()).isEqualTo(TransferMethod.AUTO);
        assertThat(loaded.getStatus()).isEqualTo(TransferStatus.MATCHED);
        assertThat(loaded.getConfirmedAt()).isNull();
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void savesAndLoadsUserLink() {
        Long id = links.saveAndFlush(TransferLink.linkedByUser(out("fp-out"), in("fp-in"), CONFIRMED)).getId();
        entityManager.clear();

        TransferLink loaded = links.findById(id).orElseThrow();

        assertThat(loaded.getMethod()).isEqualTo(TransferMethod.USER);
        assertThat(loaded.getStatus()).isEqualTo(TransferStatus.MATCHED);
        assertThat(loaded.getConfirmedAt()).isEqualTo(CONFIRMED);
    }

    @Test
    void savesOneSidedOutgoingLink() {
        Long id = links.saveAndFlush(TransferLink.oneSided(out("fp-out"), null, CONFIRMED)).getId();
        entityManager.clear();

        TransferLink loaded = links.findById(id).orElseThrow();

        assertThat(loaded.getOutTxn()).isNotNull();
        assertThat(loaded.getInTxn()).isNull();
        assertThat(loaded.getStatus()).isEqualTo(TransferStatus.UNMATCHED);
        assertThat(loaded.getMethod()).isEqualTo(TransferMethod.USER);
        assertThat(loaded.getConfirmedAt()).isEqualTo(CONFIRMED);
    }

    /** New in V9: money in from an account that isn't in the app. */
    @Test
    void savesOneSidedIncomingLink() {
        Long id = links.saveAndFlush(TransferLink.oneSided(null, in("fp-in"), CONFIRMED)).getId();
        entityManager.clear();

        TransferLink loaded = links.findById(id).orElseThrow();

        assertThat(loaded.getOutTxn()).isNull();
        assertThat(loaded.getInTxn()).isNotNull();
        assertThat(loaded.getStatus()).isEqualTo(TransferStatus.UNMATCHED);
    }

    @Test
    void oneSidedNeedsExactlyOneSide() {
        Txn out = out("fp-out");
        Txn in = in("fp-in");

        assertThatThrownBy(() -> TransferLink.oneSided(null, null, CONFIRMED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransferLink.oneSided(out, in, CONFIRMED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completingOutgoingOneSidedLinkMakesItAutoAndUnconfirmed() {
        TransferLink link = links.saveAndFlush(TransferLink.oneSided(out("fp-out"), null, CONFIRMED));
        Txn in = in("fp-in");

        link.complete(in);
        links.saveAndFlush(link);
        entityManager.clear();

        TransferLink loaded = links.findById(link.getId()).orElseThrow();
        assertThat(loaded.getInTxn().getId()).isEqualTo(in.getId());
        assertThat(loaded.getStatus()).isEqualTo(TransferStatus.MATCHED);
        assertThat(loaded.getMethod()).isEqualTo(TransferMethod.AUTO);
        assertThat(loaded.getConfirmedAt()).isNull();
    }

    @Test
    void completingIncomingOneSidedLinkFillsTheOutSide() {
        TransferLink link = links.saveAndFlush(TransferLink.oneSided(null, in("fp-in"), CONFIRMED));
        Txn out = out("fp-out");

        link.complete(out);
        links.saveAndFlush(link);
        entityManager.clear();

        TransferLink loaded = links.findById(link.getId()).orElseThrow();
        assertThat(loaded.getOutTxn().getId()).isEqualTo(out.getId());
        assertThat(loaded.getStatus()).isEqualTo(TransferStatus.MATCHED);
    }

    @Test
    void onlyUnmatchedLinksCanBeCompleted() {
        TransferLink link = TransferLink.detected(out("fp-out"), in("fp-in"));
        Txn other = in("fp-other");

        assertThatThrownBy(() -> link.complete(other)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void confirmKeepsTheFirstTime() {
        TransferLink link = links.saveAndFlush(TransferLink.detected(out("fp-out"), in("fp-in")));

        link.confirm(CONFIRMED);
        link.confirm(CONFIRMED.plusDays(1));
        links.saveAndFlush(link);
        entityManager.clear();

        assertThat(links.findById(link.getId()).orElseThrow().getConfirmedAt()).isEqualTo(CONFIRMED);
    }

    // is_transfer is kept by the V9 trigger; JPA reads it and never writes it.

    @Test
    void savingALinkMarksBothTxnsAsTransfers() {
        Txn out = out("fp-out");
        Txn in = in("fp-in");

        links.saveAndFlush(TransferLink.detected(out, in));
        entityManager.clear();

        assertThat(txns.findById(out.getId()).orElseThrow().isTransfer()).isTrue();
        assertThat(txns.findById(in.getId()).orElseThrow().isTransfer()).isTrue();
    }

    @Test
    void updatingAStaleTxnDoesNotOverwriteIsTransfer() {
        Txn out = out("fp-out");
        links.saveAndFlush(TransferLink.oneSided(out, null, CONFIRMED));
        assertThat(out.isTransfer()).isFalse(); // the managed entity hasn't seen the trigger's update

        out.setMerchant("Northfield Savings");
        txns.saveAndFlush(out);
        entityManager.clear();

        assertThat(txns.findById(out.getId()).orElseThrow().isTransfer()).isTrue();
    }

    @Test
    void excludedTxnCannotBeLinked() {
        Txn out = out("fp-out");
        out.setTransferExcluded(true);
        txns.saveAndFlush(out);

        assertThatThrownBy(() -> links.saveAndFlush(TransferLink.detected(out, in("fp-in"))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_txn_transfer_excluded");
    }

    // A txn belongs to at most one transfer link, on either side (V3 trigger).

    @Test
    void rejectsTxnAsOutSideOfTwoLinks() {
        Txn shared = out("fp-shared");
        links.saveAndFlush(TransferLink.detected(shared, in("fp-a")));

        assertThatThrownBy(() -> links.saveAndFlush(TransferLink.detected(shared, in("fp-b"))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("trg_transfer_link_txn_once");
    }

    @Test
    void rejectsTxnAsInSideOfTwoLinks() {
        Txn shared = in("fp-shared");
        links.saveAndFlush(TransferLink.detected(out("fp-a"), shared));

        assertThatThrownBy(() -> links.saveAndFlush(TransferLink.detected(out("fp-b"), shared)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("trg_transfer_link_txn_once");
    }

    @Test
    void rejectsTxnAsOutSideOfOneLinkAndInSideOfAnother() {
        Txn shared = out("fp-shared");
        links.saveAndFlush(TransferLink.detected(shared, in("fp-a")));

        assertThatThrownBy(() -> links.saveAndFlush(TransferLink.detected(out("fp-b"), shared)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("trg_transfer_link_txn_once");
    }

    @Test
    void rejectsTxnAsInSideOfOneLinkAndOutSideOfAnother() {
        Txn shared = in("fp-shared");
        links.saveAndFlush(TransferLink.detected(out("fp-a"), shared));

        assertThatThrownBy(() -> links.saveAndFlush(TransferLink.detected(shared, in("fp-b"))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("trg_transfer_link_txn_once");
    }

    @Test
    void rejectsCompletingOneSidedLinkWithTxnAlreadyLinked() {
        Txn shared = in("fp-shared");
        links.saveAndFlush(TransferLink.detected(out("fp-a"), shared));
        TransferLink pending = links.saveAndFlush(TransferLink.oneSided(out("fp-b"), null, CONFIRMED));

        pending.complete(shared);

        assertThatThrownBy(() -> links.saveAndFlush(pending))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("trg_transfer_link_txn_once");
    }

    @Test
    void twoOneSidedLinksMayEachHaveANullSide() {
        // uq_transfer_link_out and uq_transfer_link_in allow several nulls.
        assertThatCode(() -> {
            links.saveAndFlush(TransferLink.oneSided(out("fp-a"), null, CONFIRMED));
            links.saveAndFlush(TransferLink.oneSided(out("fp-b"), null, CONFIRMED));
            links.saveAndFlush(TransferLink.oneSided(null, in("fp-c"), CONFIRMED));
            links.saveAndFlush(TransferLink.oneSided(null, in("fp-d"), CONFIRMED));
        }).doesNotThrowAnyException();
    }

    private Txn out(String fingerprint) {
        return txns.saveAndFlush(EntityFixtures.txn(checking, checkingStatement, fingerprint, new BigDecimal("-250.00")));
    }

    private Txn in(String fingerprint) {
        return txns.saveAndFlush(EntityFixtures.txn(savings, savingsStatement, fingerprint, new BigDecimal("250.00")));
    }
}
