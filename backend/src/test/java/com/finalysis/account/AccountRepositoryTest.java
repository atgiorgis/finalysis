package com.finalysis.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.support.EntityFixtures;
import com.finalysis.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional // each test's inserts roll back
class AccountRepositoryTest {

    private final AccountRepository accounts;
    private final EntityManager entityManager;

    AccountRepositoryTest(AccountRepository accounts, EntityManager entityManager) {
        this.accounts = accounts;
        this.entityManager = entityManager;
    }

    @Test
    void savesAndLoadsAccount() {
        Account saved = accounts.saveAndFlush(
                new Account("Rewards Card", "Summit Card Services", AccountType.CREDIT_CARD, "0042"));
        entityManager.clear(); // load from the database, not the persistence context

        Account loaded = accounts.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getName()).isEqualTo("Rewards Card");
        assertThat(loaded.getInstitution()).isEqualTo("Summit Card Services");
        assertThat(loaded.getType()).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(loaded.getLastFour()).isEqualTo("0042");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void readsBackDatabaseGeneratedCreatedAt() {
        Account saved = accounts.saveAndFlush(EntityFixtures.account("1001"));

        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void rejectsNameLongerThanLimit() {
        Account account = new Account("x".repeat(Account.NAME_MAX + 1), "Northfield Bank", AccountType.CHECKING, "1234");

        assertThatThrownBy(() -> accounts.saveAndFlush(account))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
