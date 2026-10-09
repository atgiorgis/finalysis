package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.account.Account;
import com.finalysis.account.AccountRepository;
import com.finalysis.categorize.CategoryRepository;
import com.finalysis.categorize.CategorySource;
import com.finalysis.support.EntityFixtures;
import com.finalysis.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional // each test's inserts roll back
class TxnRepositoryTest {

    private final TxnRepository txns;
    private final StatementRepository statements;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final EntityManager entityManager;

    TxnRepositoryTest(TxnRepository txns, StatementRepository statements, AccountRepository accounts,
                      CategoryRepository categories, EntityManager entityManager) {
        this.txns = txns;
        this.statements = statements;
        this.accounts = accounts;
        this.categories = categories;
        this.entityManager = entityManager;
    }

    @Test
    void savesAndLoadsCategorizedTxnWithColumnDefaults() {
        Account account = accounts.save(EntityFixtures.account("1001"));
        Statement statement = statements.save(EntityFixtures.statement(account));
        Txn txn = EntityFixtures.txn(account, statement, "fp-groceries");
        txn.setMerchant("Greenleaf Market");
        txn.categorize(categories.findByName("Groceries").orElseThrow(), CategorySource.RULE);
        Long id = txns.saveAndFlush(txn).getId();
        entityManager.clear(); // load from the database, not the persistence context

        Txn loaded = txns.findById(id).orElseThrow();

        assertThat(loaded.getAccount().getId()).isEqualTo(account.getId());
        assertThat(loaded.getStatement().getId()).isEqualTo(statement.getId());
        assertThat(loaded.getAmount()).isEqualTo(new BigDecimal("-42.17"));
        assertThat(loaded.getMerchant()).isEqualTo("Greenleaf Market");
        assertThat(loaded.getCategory().getName()).isEqualTo("Groceries");
        assertThat(loaded.getCategorySource()).isEqualTo(CategorySource.RULE);
        assertThat(loaded.getFingerprint()).isEqualTo("fp-groceries");
        assertThat(loaded.isTransfer()).isFalse();
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void sourceTracingFieldsRoundTrip() {
        Account account = accounts.save(EntityFixtures.account("1001"));
        Statement statement = statements.save(EntityFixtures.statement(account));
        Txn txn = new Txn(account, statement, LocalDate.of(2026, 4, 9),
                "CHECK 1042", new BigDecimal("-75.00"),
                SourceSection.CHECK, "fp-tracing");
        txn.setPostDate(LocalDate.of(2026, 4, 11));
        txn.setCheckNumber("1042");
        txn.setCounterparty("J SAMPLE");
        txn.setSourceLineNumber(37);
        txn.setSourceLineText("04/11/26 CHECK 1042 1042 -75.00");
        txn.setTransfer(true);
        Long id = txns.saveAndFlush(txn).getId();
        entityManager.clear();

        Txn loaded = txns.findById(id).orElseThrow();

        assertThat(loaded.getTxnDate()).isEqualTo(LocalDate.of(2026, 4, 9));
        assertThat(loaded.getPostDate()).isEqualTo(LocalDate.of(2026, 4, 11));
        assertThat(loaded.getRawDescription()).isEqualTo("CHECK 1042");
        assertThat(loaded.getSourceSection()).isEqualTo(SourceSection.CHECK);
        assertThat(loaded.getCheckNumber()).isEqualTo("1042");
        assertThat(loaded.getCounterparty()).isEqualTo("J SAMPLE");
        assertThat(loaded.getSourceLineNumber()).isEqualTo(37);
        assertThat(loaded.getSourceLineText())
                .isEqualTo("04/11/26 CHECK 1042 1042 -75.00");
        assertThat(loaded.isTransfer()).isTrue();
    }

    @Test
    void rawDescriptionRoundTripsUnchanged() {
        // Untrimmed, double spaces, a wrapped line joined by a newline, a tab, and non-ASCII text.
        String raw = "  ZELLE PAYMENT TO  J SAMPLE\n  CONF# A1B2C3\tMEMO: café  ";
        Account account = accounts.save(EntityFixtures.account("1001"));
        Statement statement = statements.save(EntityFixtures.statement(account));
        Txn txn = new Txn(account, statement, LocalDate.of(2026, 4, 9), raw, new BigDecimal("-20.00"),
                SourceSection.WITHDRAWAL, "fp-raw");
        txn.setMerchant("Zelle");
        Long id = txns.saveAndFlush(txn).getId();
        entityManager.clear();

        Txn loaded = txns.findById(id).orElseThrow();

        assertThat(loaded.getRawDescription()).isEqualTo(raw);
        assertThat(loaded.getMerchant()).isEqualTo("Zelle");
    }

    @Test
    void savesZeroAmount() {
        Account account = accounts.save(EntityFixtures.account("1001"));
        Statement statement = statements.save(EntityFixtures.statement(account));
        Long id = txns.saveAndFlush(
                EntityFixtures.txn(account, statement, "fp-zero", new BigDecimal("0.00"))).getId();
        entityManager.clear();

        assertThat(txns.findById(id).orElseThrow().getAmount()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void rejectsDuplicateFingerprint() {
        Account account = accounts.save(EntityFixtures.account("1001"));
        Statement statement = statements.save(EntityFixtures.statement(account));
        txns.saveAndFlush(EntityFixtures.txn(account, statement, "fp-dup"));

        assertThatThrownBy(() -> txns.saveAndFlush(EntityFixtures.txn(account, statement, "fp-dup")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_txn_fingerprint");
    }

    @Test
    void rejectsTxnWhoseAccountDiffersFromStatement() {
        Account checking = accounts.save(EntityFixtures.account("1001"));
        Account savings = accounts.save(EntityFixtures.account("1002"));
        Statement checkingStatement = statements.save(EntityFixtures.statement(checking));

        assertThatThrownBy(() -> txns.saveAndFlush(EntityFixtures.txn(savings, checkingStatement, "fp-mismatch")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_txn_statement_account");
    }
}
