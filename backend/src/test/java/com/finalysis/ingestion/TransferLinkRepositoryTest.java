package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.account.Account;
import com.finalysis.account.AccountRepository;
import com.finalysis.support.EntityFixtures;
import com.finalysis.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional // each test's inserts roll back
class TransferLinkRepositoryTest {

    private final TransferLinkRepository links;
    private final TxnRepository txns;
    private final StatementRepository statements;
    private final AccountRepository accounts;
    private final EntityManager entityManager;

    private Account account;
    private Statement statement;

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
        account = accounts.save(EntityFixtures.account("1001"));
        statement = statements.save(EntityFixtures.statement(account));
    }

    @Test
    void savesAndLoadsMatchedLink() {
        Txn out = txn("fp-out");
        Txn in = txn("fp-in");
        Long id = links.saveAndFlush(new TransferLink(out, in, TransferMethod.AUTO, TransferStatus.MATCHED)).getId();
        entityManager.clear(); // load from the database, not the persistence context

        TransferLink loaded = links.findById(id).orElseThrow();

        assertThat(loaded.getOutTxn().getId()).isEqualTo(out.getId());
        assertThat(loaded.getInTxn().getId()).isEqualTo(in.getId());
        assertThat(loaded.getMethod()).isEqualTo(TransferMethod.AUTO);
        assertThat(loaded.getStatus()).isEqualTo(TransferStatus.MATCHED);
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void savesUnmatchedLinkWithoutInTxn() {
        Long id = links.saveAndFlush(
                new TransferLink(txn("fp-out"), null, TransferMethod.USER, TransferStatus.UNMATCHED)).getId();
        entityManager.clear();

        TransferLink loaded = links.findById(id).orElseThrow();

        assertThat(loaded.getInTxn()).isNull();
        assertThat(loaded.getStatus()).isEqualTo(TransferStatus.UNMATCHED);
    }

    @Test
    void rejectsMatchedLinkWithoutInTxn() {
        TransferLink link = new TransferLink(txn("fp-out"), null, TransferMethod.AUTO, TransferStatus.MATCHED);

        assertThatThrownBy(() -> links.saveAndFlush(link))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_transfer_link_status_matches_in");
    }

    // A txn belongs to at most one transfer link, on either side (V3 trigger).

    @Test
    void rejectsTxnAsOutSideOfTwoLinks() {
        Txn shared = txn("fp-shared");
        links.saveAndFlush(matched(shared, txn("fp-a")));

        assertThatThrownBy(() -> links.saveAndFlush(matched(shared, txn("fp-b"))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("trg_transfer_link_txn_once");
    }

    @Test
    void rejectsTxnAsInSideOfTwoLinks() {
        Txn shared = txn("fp-shared");
        links.saveAndFlush(matched(txn("fp-a"), shared));

        assertThatThrownBy(() -> links.saveAndFlush(matched(txn("fp-b"), shared)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("trg_transfer_link_txn_once");
    }

    @Test
    void rejectsTxnAsOutSideOfOneLinkAndInSideOfAnother() {
        Txn shared = txn("fp-shared");
        links.saveAndFlush(matched(shared, txn("fp-a")));

        assertThatThrownBy(() -> links.saveAndFlush(matched(txn("fp-b"), shared)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("trg_transfer_link_txn_once");
    }

    @Test
    void rejectsTxnAsInSideOfOneLinkAndOutSideOfAnother() {
        Txn shared = txn("fp-shared");
        links.saveAndFlush(matched(txn("fp-a"), shared));

        assertThatThrownBy(() -> links.saveAndFlush(matched(shared, txn("fp-b"))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("trg_transfer_link_txn_once");
    }

    @Test
    void rejectsMatchingUnmatchedLinkToTxnAlreadyLinked() {
        Txn shared = txn("fp-shared");
        links.saveAndFlush(matched(shared, txn("fp-a")));
        TransferLink pending = links.saveAndFlush(
                new TransferLink(txn("fp-b"), null, TransferMethod.AUTO, TransferStatus.UNMATCHED));

        pending.match(shared);

        assertThatThrownBy(() -> links.saveAndFlush(pending))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("trg_transfer_link_txn_once");
    }

    @Test
    void matchesUnmatchedLinkToUnlinkedTxn() {
        TransferLink pending = links.saveAndFlush(
                new TransferLink(txn("fp-out"), null, TransferMethod.AUTO, TransferStatus.UNMATCHED));
        Txn in = txn("fp-in");

        pending.match(in);
        links.saveAndFlush(pending);
        entityManager.clear();

        TransferLink loaded = links.findById(pending.getId()).orElseThrow();
        assertThat(loaded.getInTxn().getId()).isEqualTo(in.getId());
        assertThat(loaded.getStatus()).isEqualTo(TransferStatus.MATCHED);
    }

    private Txn txn(String fingerprint) {
        return txns.saveAndFlush(EntityFixtures.txn(account, statement, fingerprint));
    }

    private static TransferLink matched(Txn out, Txn in) {
        return new TransferLink(out, in, TransferMethod.AUTO, TransferStatus.MATCHED);
    }
}
