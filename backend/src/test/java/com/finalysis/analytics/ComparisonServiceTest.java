package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture.Loaded;
import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import com.finalysis.api.NotFoundException;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ComparisonService} (docs/plans/phase-6.md §3.3) on the analytics fixture. Expected figures are worked out by
 * hand from {@code categories.csv} and {@code expected-month-totals.csv}; the trends and month totals are used only to
 * show the comparison agrees with them, never as the expected value.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ComparisonServiceTest {

    private static final YearMonth JAN = YearMonth.of(2026, 1);
    private static final YearMonth FEB = YearMonth.of(2026, 2);
    private static final YearMonth MAR = YearMonth.of(2026, 3);
    private static final YearMonth APR = YearMonth.of(2026, 4);

    private final ImportTestSupport support;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final ComparisonService comparison;
    private final TrendsService trends;
    private final MonthlyTotalsService months;

    ComparisonServiceTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper,
                          ComparisonService comparison, TrendsService trends, MonthlyTotalsService months) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.comparison = comparison;
        this.trends = trends;
        this.months = months;
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    /** Spending March 1809.48 against February 1687.98: +121.50, 121.50 / 1687.98 = 7.197…% → 7.2. */
    @Test
    void spendingBetweenTwoMonths() throws Exception {
        load();

        ComparisonResponse r = compare(ComparisonMeasure.SPENDING, null, null, month(MAR), month(FEB));

        assertThat(r.a().value()).isEqualByComparingTo("1809.48");
        assertThat(r.b().value()).isEqualByComparingTo("1687.98");
        assertThat(r.difference()).isEqualByComparingTo("121.50");
        assertThat(r.changePercent()).isEqualByComparingTo("7.2");
        assertThat(r.a().months()).containsExactly(new ComparisonResponse.MonthCoverage(MAR, Coverage.COMPLETE));
    }

    /** Income 3001.30 against 3001.25: +0.05, 0.0017% → 0.0. Net February 1313.27 against January 1151.11: +162.16, 14.1%. */
    @Test
    void incomeAndNet() throws Exception {
        load();

        ComparisonResponse income = compare(ComparisonMeasure.INCOME, null, null, month(MAR), month(FEB));
        ComparisonResponse net = compare(ComparisonMeasure.NET, null, null, month(FEB), month(JAN));

        assertThat(income.difference()).isEqualByComparingTo("0.05");
        assertThat(income.changePercent()).isEqualByComparingTo("0.0");
        assertThat(net.a().value()).isEqualByComparingTo("1313.27");
        assertThat(net.b().value()).isEqualByComparingTo("1151.11");
        assertThat(net.difference()).isEqualByComparingTo("162.16");
        assertThat(net.changePercent()).isEqualByComparingTo("14.1");
    }

    /**
     * Dining in partial April (60.00 + 95.00 + 70.00 = 225.00) against March (50.00): +175.00, 350.0%, the trends'
     * own change for April; and against January to March (105.00 + 45.00 + 50.00 = 200.00).
     */
    @Test
    void aCategoryAgainstAMonthAndARange() throws Exception {
        long dining = categoryId("Dining");
        load();

        ComparisonResponse r = compare(ComparisonMeasure.CATEGORY_SPENT, dining, null, month(APR), month(MAR));
        ComparisonResponse range = compare(ComparisonMeasure.CATEGORY_SPENT, dining, null, month(APR),
                new MonthRange(JAN, MAR));

        assertThat(r.category().name()).isEqualTo("Dining");
        assertThat(r.a().value()).isEqualByComparingTo("225.00");
        assertThat(r.difference()).isEqualByComparingTo("175.00");
        assertThat(r.changePercent()).isEqualByComparingTo("350.0");
        assertThat(r.a().months()).containsExactly(new ComparisonResponse.MonthCoverage(APR, Coverage.PARTIAL));
        TrendRow trend = trends.trends(Optional.of(APR), Optional.empty(), Optional.of(dining)).rows().getFirst();
        assertThat(r.difference()).isEqualByComparingTo(trend.changeAmount());
        assertThat(r.changePercent()).isEqualByComparingTo(trend.changePercent());
        assertThat(range.b().value()).isEqualByComparingTo("200.00");
        assertThat(range.difference()).isEqualByComparingTo("25.00");
        assertThat(range.changePercent()).isEqualByComparingTo("12.5");
    }

    /**
     * Shopping in February is only the Target refund (+120.00), so it "spent" −120.00; January spent 145.00 (25.00 +
     * 120.00): −265.00, −265 / 145 = −182.758…% → −182.8.
     */
    @Test
    void aRefundLargerThanThePurchasesGivesANegativeMagnitude() throws Exception {
        long shopping = categoryId("Shopping");
        load();

        ComparisonResponse r = compare(ComparisonMeasure.CATEGORY_SPENT, shopping, null, month(FEB), month(JAN));

        assertThat(r.a().value()).isEqualByComparingTo("-120.00");
        assertThat(r.b().value()).isEqualByComparingTo("145.00");
        assertThat(r.difference()).isEqualByComparingTo("-265.00");
        assertThat(r.changePercent()).isEqualByComparingTo("-182.8");
    }

    /** Refunds & Rebates received 15.00 in March and nothing in February: no percent against zero. */
    @Test
    void noPercentAgainstZero() throws Exception {
        long refunds = categoryId("Refunds & Rebates");
        load();

        ComparisonResponse r = compare(ComparisonMeasure.CATEGORY_RECEIVED, refunds, null, month(MAR), month(FEB));

        assertThat(r.a().value()).isEqualByComparingTo("15.00");
        assertThat(r.b().value()).isEqualByComparingTo("0.00");
        assertThat(r.changePercent()).isNull();
    }

    /** For every month and scope, spending, income and net equal the month totals (magnitudes for spending). */
    @Test
    void itAgreesWithTheMonthTotalsInEveryMonthAndScope() throws Exception {
        Loaded loaded = load();
        for (Long account : new Long[] {null, loaded.accounts().get(AnalyticsFixture.CHECKING),
                loaded.accounts().get(AnalyticsFixture.CARD)}) {
            for (MonthTotals totals : months.months(Optional.of(YearMonth.of(2025, 12)), Optional.of(APR),
                    Optional.ofNullable(account)).months()) {
                MonthRange m = month(totals.month());
                assertThat(compare(ComparisonMeasure.SPENDING, null, account, m, m).a().value())
                        .isEqualByComparingTo(totals.spending().negate());
                assertThat(compare(ComparisonMeasure.INCOME, null, account, m, m).a().value())
                        .isEqualByComparingTo(totals.income());
                assertThat(compare(ComparisonMeasure.NET, null, account, m, m).a().value())
                        .isEqualByComparingTo(totals.net());
            }
        }
    }

    @Test
    void badRequestsAndUnknownIds() throws Exception {
        long dining = categoryId("Dining");

        assertThatThrownBy(() -> compare(ComparisonMeasure.CATEGORY_SPENT, null, null, month(MAR), month(FEB)))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getErrors())
                        .containsExactly(new InvalidField("categoryId", "is required for CATEGORY_SPENT")));
        assertThatThrownBy(() -> compare(ComparisonMeasure.SPENDING, dining, null, month(MAR), month(FEB)))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getErrors())
                        .containsExactly(new InvalidField("categoryId", "can't be combined with SPENDING")));
        assertThatThrownBy(() -> compare(ComparisonMeasure.SPENDING, null, null,
                new MonthRange(YearMonth.of(2024, 1), YearMonth.of(2026, 1)), month(FEB)))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getErrors())
                        .containsExactly(new InvalidField("a", "must be at most 24 months")));
        assertThatThrownBy(() -> compare(ComparisonMeasure.CATEGORY_SPENT, 999_999L, null, month(MAR), month(FEB)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> compare(ComparisonMeasure.SPENDING, null, 999_999L, month(MAR), month(FEB)))
                .isInstanceOf(NotFoundException.class);
    }

    private ComparisonResponse compare(ComparisonMeasure measure, Long categoryId, Long accountId, MonthRange a,
                                       MonthRange b) {
        return comparison.compare(measure, Optional.ofNullable(categoryId), Optional.ofNullable(accountId), a, b);
    }

    private static MonthRange month(YearMonth month) {
        return new MonthRange(month, month);
    }

    private long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM category WHERE name = ?", Long.class, name);
    }

    private Loaded load() throws Exception {
        return new AnalyticsFixture(support, jdbc, jsonMapper).load();
    }
}
