package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.account.Account;
import com.finalysis.account.AccountRepository;
import com.finalysis.support.EntityFixtures;
import com.finalysis.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional // each test's inserts roll back
class StatementRepositoryTest {

    private final StatementRepository statements;
    private final AccountRepository accounts;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    StatementRepositoryTest(StatementRepository statements, AccountRepository accounts, EntityManager entityManager,
                            JdbcTemplate jdbc) {
        this.statements = statements;
        this.accounts = accounts;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    @Test
    void savesAndLoadsStatementWithColumnDefaults() {
        Account account = accounts.save(EntityFixtures.account("1001"));
        Statement statement = EntityFixtures.statement(account);
        statement.setOpeningBalance(new BigDecimal("1520.00"));
        statement.setClosingBalance(new BigDecimal("1388.43"));
        Long id = statements.saveAndFlush(statement).getId();
        entityManager.clear(); // load from the database, not the persistence context

        Statement loaded = statements.findById(id).orElseThrow();

        assertThat(loaded.getAccount().getId()).isEqualTo(account.getId());
        assertThat(loaded.getPeriodStart()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(loaded.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(loaded.getOpeningBalance()).isEqualTo(new BigDecimal("1520.00"));
        assertThat(loaded.getClosingBalance()).isEqualTo(new BigDecimal("1388.43"));
        assertThat(loaded.getStatus()).isEqualTo(StatementStatus.IMPORTED);
        assertThat(loaded.getSourceFileName()).isEqualTo("northfield-2026-04.csv");
        assertThat(loaded.getFileHash()).isEqualTo(statement.getFileHash());
        assertThat(loaded.getParser()).isEqualTo("fixture");
        assertThat(loaded.getImportWarnings()).isEmpty();
        assertThat(loaded.getSectionTotals()).isEmpty();
        assertThat(loaded.getImportedAt()).isNotNull();
    }

    @Test
    void sectionTotalsRoundTripWithExactBigDecimals() {
        Account account = accounts.save(EntityFixtures.account("1001"));
        Statement statement = EntityFixtures.statement(account);
        statement.setSectionTotals(Map.of(
                SourceSection.DEPOSIT, new BigDecimal("2500.00"),
                SourceSection.WITHDRAWAL, new BigDecimal("-1234.56"),
                SourceSection.FEE, new BigDecimal("-12.5"), // stored with scale 2
                SourceSection.CHECK, new BigDecimal("0.00")));
        Long id = statements.saveAndFlush(statement).getId();
        entityManager.clear();

        Map<SourceSection, BigDecimal> loaded = statements.findById(id).orElseThrow().getSectionTotals();

        // isEqualTo on BigDecimal also compares scale, so 2500.00 must not come back as 2500 or 2.5E+3.
        assertThat(loaded).hasSize(4);
        assertThat(loaded.get(SourceSection.DEPOSIT)).isEqualTo(new BigDecimal("2500.00"));
        assertThat(loaded.get(SourceSection.WITHDRAWAL)).isEqualTo(new BigDecimal("-1234.56"));
        assertThat(loaded.get(SourceSection.FEE)).isEqualTo(new BigDecimal("-12.50"));
        assertThat(loaded.get(SourceSection.CHECK)).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void sectionTotalsAreStoredAsJsonKeyedBySectionName() {
        Account account = accounts.save(EntityFixtures.account("1001"));
        Statement statement = EntityFixtures.statement(account);
        statement.setSectionTotals(Map.of(SourceSection.FEE, new BigDecimal("-12.50")));
        Long id = statements.saveAndFlush(statement).getId();

        String json = jdbc.queryForObject("SELECT section_totals::text FROM statement WHERE id = ?", String.class, id);

        assertThat(json).isEqualTo("{\"FEE\": -12.50}");
    }

    @Test
    void rejectsSectionTotalThatDoesNotFitTheMoneyColumn() {
        Statement statement = EntityFixtures.statement(accounts.save(EntityFixtures.account("1001")));

        assertThatThrownBy(() -> statement.setSectionTotals(Map.of(SourceSection.FEE, new BigDecimal("-12.505"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("section total for FEE");
        assertThatThrownBy(() -> statement.setSectionTotals(Map.of(SourceSection.DEPOSIT, new BigDecimal("1E+10"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NUMERIC(12,2)");
    }
}
