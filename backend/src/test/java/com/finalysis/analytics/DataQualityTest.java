package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture.Loaded;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Data quality per month (ANA-08, docs/plans/phase-5.md §1.7) on the Phase 5 fixture: the plan's worked February, the
 * flags with the statement they name, and percents that are null rather than 0.0 when there is nothing to divide.
 * The fixture is imported once; a test that changes a statement puts it back.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataQualityTest {

    private static final YearMonth DEC = YearMonth.of(2025, 12);
    private static final YearMonth FEB = YearMonth.of(2026, 2);
    private static final YearMonth MAR = YearMonth.of(2026, 3);

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbc;
    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final MonthlyTotalsService months;
    private Loaded loaded;

    DataQualityTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper,
                    MonthlyTotalsService months) {
        this.mockMvc = mockMvc;
        this.jdbc = jdbc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, jsonMapper);
        this.months = months;
    }

    @BeforeAll
    void importFixture() throws Exception {
        assertThat(support.count("SELECT count(*) FROM statement")).isZero();
        loaded = fixture.load();
    }

    @AfterAll
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    /**
     * §1.7's worked example: 11 counted txns, 2 uncategorized, 1 of them review-pending; money out of the spending
     * rows 1807.98, of which 68.00 uncategorized.
     */
    @Test
    void februaryIsThePlansWorkedExample() {
        DataQuality feb = household(FEB).dataQuality();

        assertThat(feb.countedTxns()).isEqualTo(11);
        assertThat(feb.uncategorizedCount()).isEqualTo(2);
        assertThat(feb.reviewPendingCount()).isEqualTo(1);
        assertThat(feb.unlinkedTransferCount()).isEqualTo(1);
        assertThat(feb.uncategorizedPercentOfCount()).isEqualTo(new BigDecimal("18.2"));
        assertThat(feb.reviewPendingPercentOfCount()).isEqualTo(new BigDecimal("9.1"));
        assertThat(feb.uncategorizedPercentOfSpending()).isEqualTo(new BigDecimal("3.8"));
        assertThat(feb.flags()).isEmpty();
    }

    /** The Target refund (+120.00) is money in: it isn't in the denominator, so it can't shrink the share. */
    @Test
    void aRefundDoesNotShrinkTheUncategorizedShareOfSpending() {
        DataQuality card = account(AnalyticsFixture.CARD, FEB).dataQuality();
        DataQuality checking = account(AnalyticsFixture.CHECKING, FEB).dataQuality();

        assertThat(card.uncategorizedPercentOfSpending()).as("card: 0.00 of 139.98").isEqualTo(new BigDecimal("0.0"));
        assertThat(checking.uncategorizedPercentOfSpending()).as("checking: 68.00 of 1668.00")
                .isEqualTo(new BigDecimal("4.1"));
    }

    /** The checking March statement prints a final balance 10.00 too high: March is flagged, its totals unchanged. */
    @Test
    void aFailedReconciliationFlagsTheMonthWithItsStatement() {
        long statement = loaded.statements().get("fixture-checking_2026-03.csv");
        long checking = loaded.accounts().get(AnalyticsFixture.CHECKING);
        MonthFlag expected = new MonthFlag(MonthFlagType.RECONCILIATION_FAILED, checking, statement, null);

        assertThat(household(MAR).dataQuality().flags()).containsExactly(expected);
        assertThat(account(AnalyticsFixture.CHECKING, MAR).dataQuality().flags()).containsExactly(expected);
        assertThat(account(AnalyticsFixture.CARD, MAR).dataQuality().flags()).as("another account's statement")
                .isEmpty();
        assertThat(household(MAR).income()).as("the rows still count").isEqualTo(new BigDecimal("3001.30"));
    }

    /** Once the user accepts the failed statement in the review queue, the flag says when. It stays a flag. */
    @Test
    void theFlagCarriesWhenTheUserReviewedTheStatement() throws Exception {
        long statement = loaded.statements().get("fixture-checking_2026-03.csv");
        mockMvc.perform(post("/api/statements/{id}/review", statement)).andExpect(status().isOk());
        try {
            MonthFlag flag = household(MAR).dataQuality().flags().getFirst();

            assertThat(flag.type()).isEqualTo(MonthFlagType.RECONCILIATION_FAILED);
            assertThat(flag.reviewedAt()).isEqualTo(jdbc.queryForObject(
                    "SELECT reviewed_at FROM statement WHERE id = ?", java.sql.Timestamp.class, statement)
                    .toInstant());
        } finally {
            jdbc.update("UPDATE statement SET reviewed_at = NULL WHERE id = ?", statement);
        }
    }

    /**
     * A statement whose balances couldn't be checked flags every month its period overlaps: the card's Jan 15 to
     * Feb 14 cycle touches January and February.
     */
    @Test
    void anUnverifiedStatementFlagsEveryMonthItOverlaps() {
        long statement = loaded.statements().get("fixture-card_2026-02.csv");
        long card = loaded.accounts().get(AnalyticsFixture.CARD);
        jdbc.update("UPDATE statement SET balance_check = 'UNVERIFIED' WHERE id = ?", statement);
        try {
            MonthFlag expected = new MonthFlag(MonthFlagType.BALANCE_UNVERIFIED, card, statement, null);

            assertThat(household(YearMonth.of(2026, 1)).dataQuality().flags()).containsExactly(expected);
            assertThat(household(FEB).dataQuality().flags()).containsExactly(expected);
            assertThat(household(MAR).dataQuality().flags()).extracting(MonthFlag::type)
                    .containsExactly(MonthFlagType.RECONCILIATION_FAILED);
        } finally {
            jdbc.update("UPDATE statement SET balance_check = 'PASSED' WHERE id = ?", statement);
        }
    }

    /** December: only the card is expected, from the 15th, so the household month is partial because of it. */
    @Test
    void aPartialMonthNamesTheAccountMissingDays() {
        long card = loaded.accounts().get(AnalyticsFixture.CARD);

        assertThat(household(DEC).dataQuality().flags())
                .containsExactly(new MonthFlag(MonthFlagType.PARTIAL_COVERAGE, card, null, null));
    }

    /** An account with no txn in the month has nothing to divide: every percent is null, not 0.0. */
    @Test
    void percentsAreNullWhenNothingIsCounted() {
        DataQuality dec = account(AnalyticsFixture.CHECKING, DEC).dataQuality();

        assertThat(dec.countedTxns()).isZero();
        assertThat(dec.uncategorizedPercentOfCount()).isNull();
        assertThat(dec.reviewPendingPercentOfCount()).isNull();
        assertThat(dec.uncategorizedPercentOfSpending()).isNull();
        assertThat(dec.flags()).as("no coverage at all is NONE, not PARTIAL").isEmpty();
        assertThat(account(AnalyticsFixture.CHECKING, DEC).savingsRatePercent()).isNull();
    }

    private MonthTotals household(YearMonth month) {
        return months.months(Optional.of(month), Optional.of(month), Optional.empty()).months().getFirst();
    }

    private MonthTotals account(String slug, YearMonth month) {
        return months.months(Optional.of(month), Optional.of(month), Optional.of(loaded.accounts().get(slug)))
                .months().getFirst();
    }
}
