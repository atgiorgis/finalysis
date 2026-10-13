package com.finalysis.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.Account;
import com.finalysis.account.AccountRepository;
import com.finalysis.ingestion.Statement;
import com.finalysis.ingestion.StatementRepository;
import com.finalysis.ingestion.TxnRepository;
import com.finalysis.support.EntityFixtures;
import com.finalysis.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional // each test's inserts roll back
class TransferRejectionRepositoryTest {

    private final TransferRejectionRepository rejections;
    private final TxnRepository txns;
    private final StatementRepository statements;
    private final AccountRepository accounts;
    private final EntityManager entityManager;

    TransferRejectionRepositoryTest(TransferRejectionRepository rejections, TxnRepository txns,
                                    StatementRepository statements, AccountRepository accounts,
                                    EntityManager entityManager) {
        this.rejections = rejections;
        this.txns = txns;
        this.statements = statements;
        this.accounts = accounts;
        this.entityManager = entityManager;
    }

    @Test
    void savesAndFindsByPair() {
        Account checking = accounts.save(EntityFixtures.account("1001"));
        Statement checkingStatement = statements.save(EntityFixtures.statement(checking));
        Account savings = accounts.save(EntityFixtures.account("1002"));
        Statement savingsStatement = statements.save(EntityFixtures.statement(savings));
        long out = txns.saveAndFlush(EntityFixtures.txn(checking, checkingStatement, "fp-out",
                new BigDecimal("-250.00"))).getId();
        long in = txns.saveAndFlush(EntityFixtures.txn(savings, savingsStatement, "fp-in",
                new BigDecimal("250.00"))).getId();

        rejections.saveAndFlush(new TransferRejection(out, in));
        entityManager.clear();

        TransferRejection loaded = rejections.findById(new TransferRejection.Pair(out, in)).orElseThrow();
        assertThat(loaded.getPair()).isEqualTo(new TransferRejection.Pair(out, in));
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(rejections.existsById(new TransferRejection.Pair(in, out))).isFalse();
    }
}
