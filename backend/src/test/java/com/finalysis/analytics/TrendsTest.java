package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture.Loaded;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Trends (ANA-03, docs/plans/phase-5.md §7). On the fixture, figures hand-computed from {@code categories.csv}: the
 * household is partial in December and April, complete January to March, and has no data before December. On the
 * sample data, with the answer key's categories, the plan's own worked examples.
 */
@IntegrationTest
@AutoConfigureMockMvc
class TrendsTest {

    private static final YearMonth DEC = YearMonth.of(2025, 12);
    private static final YearMonth FEB = YearMonth.of(2026, 2);
    private static final YearMonth MAR = YearMonth.of(2026, 3);
    private static final YearMonth APR = YearMonth.of(2026, 4);

    private final ImportTestSupport support;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final TrendsService trends;

    TrendsTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper,
               TrendsService trends) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.trends = trends;
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    /**
     * Dining in April: 60.00 + 95.00 + 70.00. March (50.00) is the previous month; January to March, all complete,
     * are the trailing months: (105.00 + 45.00 + 50.00) / 3 = 66.666… → 66.67. The six-month window reaches back to
     * October: November and October have no data and December is partial, so it uses the same three months.
     */
    @Test
    void aPartialMonthIsComparedWithCompleteMonthsOnly() throws Exception {
        load();
        TrendRow dining = row(household(APR), TrendLine.CATEGORY, "Dining");

        assertThat(dining.measure()).isEqualTo(TrendMeasure.SPENT);
        assertThat(dining.bucket()).isEqualTo(TxnBucket.EXPENSE);
        assertThat(dining.amount()).isEqualTo(money("225.00"));
        assertThat(dining.previousMonth()).isEqualTo(money("50.00"));
        assertThat(dining.changeAmount()).isEqualTo(money("175.00"));
        assertThat(dining.changePercent()).isEqualTo(new BigDecimal("350.0"));
        assertThat(dining.trailing3()).isEqualTo(new Average(money("66.67"), 3, 3));
        assertThat(dining.trailing6()).isEqualTo(new Average(money("66.67"), 3, 6));
        assertThat(dining.sameMonthLastYear()).as("2025-04 is NONE").isNull();
        assertThat(dining.monthCoverage()).isEqualTo(Coverage.PARTIAL);
    }

    /** The default month is the latest complete one; December, partial, is skipped: (105.00 + 45.00) / 2. */
    @Test
    void theDefaultMonthIsTheLatestCompleteOne() throws Exception {
        load();
        TrendsResponse response = trends.trends(Optional.empty(), Optional.empty(), Optional.empty());
        TrendRow dining = row(response, TrendLine.CATEGORY, "Dining");

        assertThat(response.month()).isEqualTo(MAR);
        assertThat(dining.amount()).isEqualTo(money("50.00"));
        assertThat(dining.changePercent()).as("5.00 / 45.00").isEqualTo(new BigDecimal("11.1"));
        assertThat(dining.trailing3()).isEqualTo(new Average(money("75.00"), 2, 3));
        assertThat(dining.monthCoverage()).isEqualTo(Coverage.COMPLETE);
    }

    /** Before the data: November is NONE, so there is nothing to compare with, and no complete month to average. */
    @Test
    void theFirstMonthHasNothingToCompareWith() throws Exception {
        load();
        TrendRow dining = row(household(DEC), TrendLine.CATEGORY, "Dining");

        assertThat(dining.amount()).isEqualTo(money("40.00"));
        assertThat(dining.previousMonth()).isNull();
        assertThat(dining.changeAmount()).isNull();
        assertThat(dining.changePercent()).isNull();
        assertThat(dining.trailing3()).isEqualTo(new Average(null, 0, 3));
        assertThat(dining.trailing6()).isEqualTo(new Average(null, 0, 6));
    }

    /** §1.2: the Target refund makes February's Shopping negative: refunds exceed purchases. */
    @Test
    void spentIsNegativeWhenRefundsExceedPurchases() throws Exception {
        load();
        TrendRow shopping = row(household(FEB), TrendLine.CATEGORY, "Shopping");

        assertThat(shopping.amount()).isEqualTo(money("-120.00"));
        assertThat(shopping.previousMonth()).as("Target 120.00 and bookshop 25.00").isEqualTo(money("145.00"));
        assertThat(shopping.changeAmount()).isEqualTo(money("-265.00"));
        assertThat(shopping.changePercent()).as("−265.00 / 145.00").isEqualTo(new BigDecimal("-182.8"));
        assertThat(shopping.trailing3()).as("January only").isEqualTo(new Average(money("145.00"), 1, 3));
    }

    /**
     * March's totals and the lines without a category. Total spending's average is (1687.98 + 1849.99) / 2 = 1768.985,
     * which rounds half to even to 1768.98; total income's is (3001.25 + 3001.10) / 2 = 3001.175 → 3001.18.
     */
    @Test
    void totalsRefundsAndUncategorized() throws Exception {
        load();
        TrendsResponse march = household(MAR);

        TrendRow spending = row(march, TrendLine.TOTAL_SPENDING, null);
        assertThat(spending.measure()).isEqualTo(TrendMeasure.SPENT);
        assertThat(spending.amount()).isEqualTo(money("1809.48"));
        assertThat(spending.previousMonth()).isEqualTo(money("1687.98"));
        assertThat(spending.changeAmount()).isEqualTo(money("121.50"));
        assertThat(spending.changePercent()).isEqualTo(new BigDecimal("7.2"));
        assertThat(spending.trailing3()).isEqualTo(new Average(money("1768.98"), 2, 3));

        TrendRow income = row(march, TrendLine.TOTAL_INCOME, null);
        assertThat(income.measure()).isEqualTo(TrendMeasure.RECEIVED);
        assertThat(income.amount()).isEqualTo(money("3001.30"));
        assertThat(income.changePercent()).as("0.05 / 3001.25").isEqualTo(new BigDecimal("0.0"));
        assertThat(income.trailing3()).isEqualTo(new Average(money("3001.18"), 2, 3));

        TrendRow refunds = row(march, TrendLine.CATEGORY, "Refunds & Rebates");
        assertThat(refunds.measure()).as("money back").isEqualTo(TrendMeasure.RECEIVED);
        assertThat(refunds.bucket()).isEqualTo(TxnBucket.REFUND);
        assertThat(refunds.amount()).isEqualTo(money("15.00"));
        assertThat(refunds.previousMonth()).isEqualTo(money("0.00"));
        assertThat(refunds.changePercent()).as("previous month 0").isNull();

        TrendRow uncategorized = row(march, TrendLine.UNCATEGORIZED, null);
        assertThat(uncategorized.amount()).isEqualTo(money("0.00"));
        assertThat(uncategorized.previousMonth()).isEqualTo(money("68.00"));
        assertThat(uncategorized.changePercent()).isEqualTo(new BigDecimal("-100.0"));
    }

    @Test
    void rowsComeSpendingFirstThenIncomeThenRefunds() throws Exception {
        load();
        List<String> order = household(MAR).rows().stream()
                .map(r -> r.line() + (r.category() == null ? "" : ":" + r.category().name())).toList();

        assertThat(order).containsExactly("TOTAL_SPENDING",
                "CATEGORY:Dining", "CATEGORY:Groceries", "CATEGORY:Housing", "CATEGORY:Person-to-Person Out",
                "CATEGORY:Shopping", "CATEGORY:Subscriptions", "CATEGORY:Transfer",
                "UNCATEGORIZED", "TOTAL_INCOME", "CATEGORY:Income", "CATEGORY:Interest",
                "CATEGORY:Refunds & Rebates");
    }

    @Test
    void filtersByAccountAndCategory() throws Exception {
        Loaded loaded = load();
        long dining = jdbc.queryForObject("SELECT id FROM category WHERE name = 'Dining'", Long.class);

        TrendsResponse only = trends.trends(Optional.of(MAR), Optional.empty(), Optional.of(dining));
        assertThat(only.rows()).singleElement().satisfies(r -> assertThat(r.category().name()).isEqualTo("Dining"));

        TrendsResponse checking = trends.trends(Optional.of(MAR),
                Optional.of(loaded.accounts().get(AnalyticsFixture.CHECKING)), Optional.empty());
        assertThat(checking.rows()).extracting(r -> r.category() == null ? r.line().name() : r.category().name())
                .as("all dining is on the card").doesNotContain("Dining")
                .contains("Housing", "Income");
        assertThat(row(checking, TrendLine.TOTAL_SPENDING, null).amount()).isEqualTo(money("1535.00"));
    }

    /**
     * docs/plans/phase-5.md §6.3 and §7's worked examples, on the sample data with the answer key's categories:
     * Dining 2026-08 against May to July, and 2026-09's six months, which skip the partial March.
     */
    @Test
    void thePlansSampleDataExamples() throws Exception {
        SampleDataImport sample = new SampleDataImport(support, jsonMapper);
        assertThat(support.count("SELECT count(*) FROM statement")).isZero();
        Map<String, Long> accounts = sample.load();
        sample.applyAnswerKey(jdbc, accounts);

        TrendRow augustDining = row(household(YearMonth.of(2026, 8)), TrendLine.CATEGORY, "Dining");
        assertThat(augustDining.amount()).isEqualTo(money("340.63"));
        assertThat(augustDining.trailing3()).isEqualTo(new Average(money("207.79"), 3, 3));

        TrendRow augustShopping = row(household(YearMonth.of(2026, 8)), TrendLine.CATEGORY, "Shopping");
        assertThat(augustShopping.amount()).isEqualTo(money("1183.67"));
        assertThat(augustShopping.trailing3()).isEqualTo(new Average(money("244.42"), 3, 3));

        TrendRow septemberDining = row(household(YearMonth.of(2026, 9)), TrendLine.CATEGORY, "Dining");
        assertThat(septemberDining.amount()).isEqualTo(money("356.91"));
        assertThat(septemberDining.trailing3()).isEqualTo(new Average(money("263.08"), 3, 3));
        assertThat(septemberDining.trailing6()).isEqualTo(new Average(money("222.49"), 5, 6));
        assertThat(septemberDining.sameMonthLastYear()).as("2025-09 is NONE").isNull();
    }

    /**
     * The range overload the category spike check uses (one bucket query for every month) gives each month exactly the
     * per-month answer: on the fixture from two months before its data to two after, and on an empty database.
     */
    @Test
    void theRangeOverloadEqualsEachMonthsTrends() throws Exception {
        MonthRange range = new MonthRange(YearMonth.of(2025, 10), YearMonth.of(2026, 6));
        assertThat(trends.trends(range)).extracting(TrendsResponse::month).containsExactlyElementsOf(range.months());
        assertThat(trends.trends(range)).allSatisfy(r -> assertThat(r.rows()).isEmpty());

        load();
        List<TrendsResponse> all = trends.trends(range);

        assertThat(all).hasSize(9);
        for (TrendsResponse month : all) {
            assertThat(month).as(month.month().toString()).isEqualTo(household(month.month()));
        }
        assertThat(all.get(6).rows()).as("April has rows").isNotEmpty();
    }

    private Loaded load() throws Exception {
        assertThat(support.count("SELECT count(*) FROM statement")).isZero();
        return new AnalyticsFixture(support, jdbc, jsonMapper).load();
    }

    private TrendsResponse household(YearMonth month) {
        return trends.trends(Optional.of(month), Optional.empty(), Optional.empty());
    }

    private static TrendRow row(TrendsResponse response, TrendLine line, String category) {
        List<TrendRow> matching = response.rows().stream()
                .filter(r -> r.line() == line)
                .filter(r -> category == null || (r.category() != null && r.category().name().equals(category)))
                .toList();
        assertThat(matching).as(line + " " + category).hasSize(1);
        return matching.getFirst();
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
