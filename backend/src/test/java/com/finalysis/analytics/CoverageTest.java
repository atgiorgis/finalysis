package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.analytics.AnalyticsFixture.Loaded;
import com.finalysis.analytics.CoverageResponse.AccountCoverage;
import com.finalysis.analytics.CoverageResponse.DateSpan;
import com.finalysis.analytics.CoverageResponse.FlaggedStatement;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Coverage on the Phase 5 fixture (docs/plans/phase-5.md §1.6, §9.1): the card's first statement starts on
 * 2025-12-15 and its last ends on 2026-04-14, while checking covers January to April by calendar month. So December
 * and April are partial, January to March complete. Statement periods come from {@code manifest.csv}.
 */
@IntegrationTest
@AutoConfigureMockMvc
class CoverageTest {

    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final JdbcTemplate jdbc;
    private final CoverageService coverage;
    private final MonthlyTotalsService months;

    CoverageTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper,
                 CoverageService coverage, MonthlyTotalsService months) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, jsonMapper);
        this.jdbc = jdbc;
        this.coverage = coverage;
        this.months = months;
    }

    @BeforeEach
    void databaseHasNoStatements() {
        assertThat(support.count("SELECT count(*) FROM statement")).isZero();
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @Test
    void theFixturesMonths() throws Exception {
        fixture.load();

        assertThat(months.months(Optional.of(YearMonth.of(2025, 11)), Optional.of(YearMonth.of(2026, 5)),
                Optional.empty()).months()).extracting(MonthTotals::coverage).containsExactly(
                Coverage.NONE, Coverage.PARTIAL, Coverage.COMPLETE, Coverage.COMPLETE, Coverage.COMPLETE,
                Coverage.PARTIAL, Coverage.NONE);
    }

    @Test
    void eachAccountsCoveredDaysGapsAndFlaggedStatements() throws Exception {
        Loaded loaded = fixture.load();
        CoverageResponse response = coverage.coverage();

        assertThat(response.lastCoveredDay()).isEqualTo(LocalDate.parse("2026-04-30"));
        assertThat(response.latestMonth()).isEqualTo(YearMonth.of(2026, 4));
        assertThat(response.latestCompleteMonth()).isEqualTo(YearMonth.of(2026, 3));

        AccountCoverage checking = response.accounts().getFirst();
        assertThat(checking.accountId()).isEqualTo(loaded.accounts().get(AnalyticsFixture.CHECKING));
        assertThat(checking.firstDay()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(checking.covered()).as("four calendar months merge into one span")
                .containsExactly(span("2026-01-01", "2026-04-30"));
        assertThat(checking.gaps()).isEmpty();
        assertThat(checking.flaggedStatements()).containsExactly(new FlaggedStatement(
                loaded.statements().get("fixture-checking_2026-03.csv"), LocalDate.parse("2026-03-01"),
                LocalDate.parse("2026-03-31"), MonthFlagType.RECONCILIATION_FAILED, null));

        AccountCoverage card = response.accounts().get(1);
        assertThat(card.firstDay()).isEqualTo(LocalDate.parse("2025-12-15"));
        assertThat(card.covered()).containsExactly(span("2025-12-15", "2026-04-14"));
        assertThat(card.gaps()).as("expected up to checking's last day").containsExactly(span("2026-04-15", "2026-04-30"));
        assertThat(card.flaggedStatements()).isEmpty();
        assertThat(card.truncated()).isFalse();
    }

    /** Deleting the card's February statement opens a gap: January and February become partial for the household. */
    @Test
    void aMissingStatementMakesItsMonthsPartial() throws Exception {
        Loaded loaded = fixture.load();
        jdbc.update("DELETE FROM statement WHERE id = ?", loaded.statements().get("fixture-card_2026-02.csv"));

        CoverageResponse response = coverage.coverage();
        assertThat(response.accounts().get(1).gaps())
                .containsExactly(span("2026-01-15", "2026-02-14"), span("2026-04-15", "2026-04-30"));
        assertThat(months.months(Optional.of(YearMonth.of(2026, 1)), Optional.of(YearMonth.of(2026, 3)),
                Optional.empty()).months()).extracting(MonthTotals::coverage)
                .containsExactly(Coverage.PARTIAL, Coverage.PARTIAL, Coverage.COMPLETE);
        assertThat(response.latestCompleteMonth()).isEqualTo(YearMonth.of(2026, 3));
    }

    @Test
    void anAccountWithoutStatementsIsListedEmpty() {
        long id = support.account(AccountType.SAVINGS, "5301");

        CoverageResponse response = coverage.coverage();
        assertThat(response.lastCoveredDay()).isNull();
        assertThat(response.latestMonth()).isNull();
        assertThat(response.accounts()).filteredOn(account -> account.accountId() == id).singleElement()
                .satisfies(account -> {
            assertThat(account.firstDay()).isNull();
            assertThat(account.covered()).isEmpty();
            assertThat(account.gaps()).isEmpty();
        });
    }

    private static DateSpan span(String start, String end) {
        return new DateSpan(LocalDate.parse(start), LocalDate.parse(end));
    }
}
