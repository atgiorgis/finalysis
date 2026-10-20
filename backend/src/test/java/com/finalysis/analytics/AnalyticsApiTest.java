package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture.Loaded;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The analytics endpoints over HTTP on the Phase 5 fixture (docs/plans/phase-5.md §5, §9.5): defaults, units as JSON
 * strings (money with two decimals, percents with one, months {@code yyyy-MM}), every 400 with the parameter it
 * names, and 404 for an unknown id. The figures are the fixture's hand-computed ones. The empty database is
 * {@link AnalyticsEmptyDatabaseTest}.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsApiTest {

    private static final String MONTHS = "/api/analytics/months";
    private static final String CATEGORIES = "/api/analytics/categories";
    private static final String ACCOUNTS = "/api/analytics/accounts";
    private static final String COVERAGE = "/api/analytics/coverage";

    private final MockMvc mockMvc;
    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final JdbcTemplate jdbc;
    private Loaded loaded;

    AnalyticsApiTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.mockMvc = mockMvc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, jsonMapper);
        this.jdbc = jdbc;
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

    /** Without parameters: six months ending at the month of the last covered day, 2026-04. */
    @Test
    void monthsDefaultToSixMonthsEndingAtTheLatestMonth() throws Exception {
        mockMvc.perform(get(MONTHS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range.from").value("2025-11"))
                .andExpect(jsonPath("$.range.to").value("2026-04"))
                .andExpect(jsonPath("$.accountId").isEmpty())
                .andExpect(jsonPath("$.latestMonth").value("2026-04"))
                .andExpect(jsonPath("$.latestCompleteMonth").value("2026-03"))
                .andExpect(jsonPath("$.months.length()").value(6))
                .andExpect(jsonPath("$.months[0].month").value("2025-11"))
                .andExpect(jsonPath("$.months[0].coverage").value("NONE"))
                .andExpect(jsonPath("$.months[0].income").value("0.00"));
    }

    /** February as JSON: money strings with two decimals, percents with one, the review-pending count. */
    @Test
    void moneyAndPercentsAreStrings() throws Exception {
        mockMvc.perform(get(MONTHS).param("from", "2026-02").param("to", "2026-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months[0].month").value("2026-02"))
                .andExpect(jsonPath("$.months[0].coverage").value("COMPLETE"))
                .andExpect(jsonPath("$.months[0].income").value("3001.25"))
                .andExpect(jsonPath("$.months[0].spending").value("-1687.98"))
                .andExpect(jsonPath("$.months[0].net").value("1313.27"))
                .andExpect(jsonPath("$.months[0].savingsRatePercent").value("43.8"))
                .andExpect(jsonPath("$.months[0].refunds.moneyIn").value("0.00"))
                .andExpect(jsonPath("$.months[0].uncategorized.moneyOut").value("-68.00"))
                .andExpect(jsonPath("$.months[0].uncategorized.count").value(2))
                .andExpect(jsonPath("$.months[0].unlinkedTransfers.net").value("-250.00"))
                .andExpect(jsonPath("$.months[0].linkedTransfers.moneyIn").value("400.00"))
                .andExpect(jsonPath("$.months[0].netChange").value("1063.27"))
                .andExpect(jsonPath("$.months[0].txnCount").value(14))
                .andExpect(jsonPath("$.months[0].dataQuality.countedTxns").value(11))
                .andExpect(jsonPath("$.months[0].dataQuality.reviewPendingCount").value(1))
                .andExpect(jsonPath("$.months[0].dataQuality.uncategorizedPercentOfCount").value("18.2"))
                .andExpect(jsonPath("$.months[0].dataQuality.reviewPendingPercentOfCount").value("9.1"))
                .andExpect(jsonPath("$.months[0].dataQuality.uncategorizedPercentOfSpending").value("3.8"))
                .andExpect(jsonPath("$.months[0].dataQuality.flags.length()").value(0));
    }

    /** December: no income, so no savings rate; the card's partial coverage is a flag naming the account. */
    @Test
    void nullsAndFlags() throws Exception {
        long card = loaded.accounts().get(AnalyticsFixture.CARD);
        long checking = loaded.accounts().get(AnalyticsFixture.CHECKING);
        mockMvc.perform(get(MONTHS).param("from", "2025-12").param("to", "2026-03"))
                .andExpect(jsonPath("$.months[0].savingsRatePercent").isEmpty())
                .andExpect(jsonPath("$.months[0].dataQuality.flags[0].type").value("PARTIAL_COVERAGE"))
                .andExpect(jsonPath("$.months[0].dataQuality.flags[0].accountId").value(card))
                .andExpect(jsonPath("$.months[0].dataQuality.flags[0].statementId").isEmpty())
                .andExpect(jsonPath("$.months[3].dataQuality.flags[0].type").value("RECONCILIATION_FAILED"))
                .andExpect(jsonPath("$.months[3].dataQuality.flags[0].accountId").value(checking))
                .andExpect(jsonPath("$.months[3].dataQuality.flags[0].statementId")
                        .value(loaded.statements().get("fixture-checking_2026-03.csv")));
        mockMvc.perform(get(MONTHS).param("from", "2025-12").param("to", "2025-12").param("accountId", "" + checking))
                .andExpect(jsonPath("$.accountId").value(checking))
                .andExpect(jsonPath("$.months[0].dataQuality.uncategorizedPercentOfCount").isEmpty());
    }

    @Test
    void eitherEndAloneSetsTheOther() throws Exception {
        mockMvc.perform(get(MONTHS).param("to", "2026-02"))
                .andExpect(jsonPath("$.range.from").value("2025-09"))
                .andExpect(jsonPath("$.range.to").value("2026-02"));
        mockMvc.perform(get(MONTHS).param("from", "2026-01"))
                .andExpect(jsonPath("$.range.from").value("2026-01"))
                .andExpect(jsonPath("$.range.to").value("2026-04"));
        mockMvc.perform(get(MONTHS).param("from", "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range.to").value("2026-07"))
                .andExpect(jsonPath("$.months[0].coverage").value("NONE"));
    }

    @Test
    void categoriesAsJson() throws Exception {
        mockMvc.perform(get(CATEGORIES).param("from", "2026-02").param("to", "2026-02").param("uncategorized", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uncategorized").value(true))
                .andExpect(jsonPath("$.coverage[0].month").value("2026-02"))
                .andExpect(jsonPath("$.coverage[0].coverage").value("COMPLETE"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].bucket").value("UNCATEGORIZED"))
                .andExpect(jsonPath("$.rows[0].category").isEmpty())
                .andExpect(jsonPath("$.rows[0].total.moneyOut").value("-68.00"))
                .andExpect(jsonPath("$.rows[0].months[0].flow.net").value("-68.00"))
                .andExpect(jsonPath("$.rows[0].months[0].reviewPending").value(1));
        long refunds = jdbc.queryForObject("SELECT id FROM category WHERE name = 'Refunds & Rebates'", Long.class);
        mockMvc.perform(get(CATEGORIES).param("from", "2026-03").param("to", "2026-03")
                        .param("categoryId", "" + refunds))
                .andExpect(jsonPath("$.categoryId").value(refunds))
                .andExpect(jsonPath("$.rows[0].bucket").value("REFUND"))
                .andExpect(jsonPath("$.rows[0].category.name").value("Refunds & Rebates"))
                .andExpect(jsonPath("$.rows[0].category.kind").value("INCOME"))
                .andExpect(jsonPath("$.rows[0].months[0].flow.moneyIn").value("15.00"))
                .andExpect(jsonPath("$.rows[0].months[0].reviewPending").isEmpty());
    }

    @Test
    void accountsAsJson() throws Exception {
        mockMvc.perform(get(ACCOUNTS).param("from", "2026-01").param("to", "2026-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range.from").value("2026-01"))
                .andExpect(jsonPath("$.latestMonth").value("2026-04"))
                .andExpect(jsonPath("$.accounts[0].accountId").value(loaded.accounts().get(AnalyticsFixture.CHECKING)))
                .andExpect(jsonPath("$.accounts[0].name").value("Fixture Checking"))
                .andExpect(jsonPath("$.accounts[0].months[0].netChange").value("1111.10"))
                .andExpect(jsonPath("$.accounts[1].months[0].netChange").value("40.01"))
                .andExpect(jsonPath("$.accounts[1].months[0].linkedTransfers.moneyIn").value("300.00"));
    }

    @Test
    void coverageAsJson() throws Exception {
        mockMvc.perform(get(COVERAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCoveredDay").value("2026-04-30"))
                .andExpect(jsonPath("$.latestMonth").value("2026-04"))
                .andExpect(jsonPath("$.latestCompleteMonth").value("2026-03"))
                .andExpect(jsonPath("$.accounts[1].firstDay").value("2025-12-15"))
                .andExpect(jsonPath("$.accounts[1].covered[0].start").value("2025-12-15"))
                .andExpect(jsonPath("$.accounts[1].covered[0].end").value("2026-04-14"))
                .andExpect(jsonPath("$.accounts[1].gaps[0].start").value("2026-04-15"))
                .andExpect(jsonPath("$.accounts[0].flaggedStatements[0].flag").value("RECONCILIATION_FAILED"))
                .andExpect(jsonPath("$.accounts[0].flaggedStatements[0].reviewedAt").isEmpty())
                .andExpect(jsonPath("$.accounts[0].truncated").value(false));
    }

    @Test
    void merchantsAsJson() throws Exception {
        mockMvc.perform(get("/api/analytics/merchants").param("from", "2025-12").param("to", "2026-04")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.items[0].merchant").value("FIXTURE PROPERTY RENT"))
                .andExpect(jsonPath("$.items[0].spent").value("6000.00"))
                .andExpect(jsonPath("$.items[0].firstDate").value("2026-01-03"))
                .andExpect(jsonPath("$.items[0].category.name").value("Housing"))
                .andExpect(jsonPath("$.items[1].spent").value("700.00"))
                .andExpect(jsonPath("$.othersSpent").value("1157.43"))
                .andExpect(jsonPath("$.totalSpent").value("7857.43"));
        mockMvc.perform(get("/api/analytics/merchants")).andExpect(jsonPath("$.limit").value(10));
        mockMvc.perform(get("/api/analytics/merchants").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"))
                .andExpect(jsonPath("$.errors[0].message").value("must be 1 to 50"));
        mockMvc.perform(get("/api/analytics/merchants").param("limit", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/analytics/merchants").param("limit", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("must be a whole number"));
    }

    @Test
    void trendsAsJson() throws Exception {
        mockMvc.perform(get("/api/analytics/trends").param("month", "2026-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-04"))
                .andExpect(jsonPath("$.rows[0].line").value("TOTAL_SPENDING"))
                .andExpect(jsonPath("$.rows[0].measure").value("SPENT"))
                .andExpect(jsonPath("$.rows[0].category").isEmpty())
                .andExpect(jsonPath("$.rows[1].category.name").value("Dining"))
                .andExpect(jsonPath("$.rows[1].bucket").value("EXPENSE"))
                .andExpect(jsonPath("$.rows[1].amount").value("225.00"))
                .andExpect(jsonPath("$.rows[1].changePercent").value("350.0"))
                .andExpect(jsonPath("$.rows[1].trailing3.amount").value("66.67"))
                .andExpect(jsonPath("$.rows[1].trailing3.monthsUsed").value(3))
                .andExpect(jsonPath("$.rows[1].trailing6.monthsWanted").value(6))
                .andExpect(jsonPath("$.rows[1].sameMonthLastYear").isEmpty())
                .andExpect(jsonPath("$.rows[1].monthCoverage").value("PARTIAL"));
        mockMvc.perform(get("/api/analytics/trends")).andExpect(jsonPath("$.month").value("2026-03"));
        mockMvc.perform(get("/api/analytics/trends").param("month", "2026-3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("month"));
        long unknown = jdbc.queryForObject("SELECT max(id) + 1000 FROM account", Long.class);
        mockMvc.perform(get("/api/analytics/trends").param("accountId", "" + unknown))
                .andExpect(status().isNotFound());
        long unknownCategory = jdbc.queryForObject("SELECT max(id) + 1000 FROM category", Long.class);
        mockMvc.perform(get("/api/analytics/merchants").param("categoryId", "" + unknownCategory))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ errors

    @Test
    void invalidMonthsAre400sNamingTheParameter() throws Exception {
        for (String path : new String[] {MONTHS, CATEGORIES, ACCOUNTS}) {
            for (String month : new String[] {"2026-13", "2026-1", "April", "2026-02-01"}) {
                mockMvc.perform(get(path).param("from", month))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.title").value("Invalid request"))
                        .andExpect(jsonPath("$.errors[0].field").value("from"))
                        .andExpect(jsonPath("$.errors[0].message").value("must be a month (yyyy-MM)"))
                        .andExpect(content().string(not(containsString(month))));
            }
            mockMvc.perform(get(path).param("from", "2026-03").param("to", "2026-02"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("to"))
                    .andExpect(jsonPath("$.errors[0].message").value("must not be before from"));
        }
    }

    /** At most 24 months, both ends included. */
    @Test
    void aRangeLongerThanTheMaximumIsA400() throws Exception {
        mockMvc.perform(get(MONTHS).param("from", "2024-05").param("to", "2026-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months.length()").value(24));
        mockMvc.perform(get(MONTHS).param("from", "2024-04").param("to", "2026-04"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("to"))
                .andExpect(jsonPath("$.errors[0].message").value("the range must be at most 24 months, both ends included"));
        mockMvc.perform(get(CATEGORIES).param("from", "2024-04").param("to", "2026-04"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(ACCOUNTS).param("from", "2024-04").param("to", "2026-04"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(MONTHS).param("from", "2024-04"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].message").value(
                        "the range must be at most 24 months, both ends included; without to, it ends at 2026-04"));
    }

    @Test
    void otherInvalidParametersAre400s() throws Exception {
        mockMvc.perform(get(CATEGORIES).param("categoryId", "1").param("uncategorized", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("uncategorized"))
                .andExpect(jsonPath("$.errors[0].message").value("can't be combined with categoryId"));
        mockMvc.perform(get(CATEGORIES).param("uncategorized", "maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("must be true or false"));
        mockMvc.perform(get(MONTHS).param("accountId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("accountId"))
                .andExpect(jsonPath("$.errors[0].message").value("must be a whole number"));
        mockMvc.perform(get(CATEGORIES).param("categoryId", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));
    }

    /** An unknown id is an error, so "0.00 spent on account 99" can't be read as an answer (§5.1). */
    @Test
    void unknownIdsAre404s() throws Exception {
        long unknown = jdbc.queryForObject("SELECT coalesce(max(id), 0) + 1000 FROM account", Long.class);
        long unknownCategory = jdbc.queryForObject("SELECT coalesce(max(id), 0) + 1000 FROM category", Long.class);

        mockMvc.perform(get(MONTHS).param("accountId", "" + unknown))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(get(CATEGORIES).param("accountId", "" + unknown)).andExpect(status().isNotFound());
        mockMvc.perform(get(CATEGORIES).param("categoryId", "" + unknownCategory)).andExpect(status().isNotFound());
    }
}
