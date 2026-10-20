package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.DrillDownTest.Filter;
import com.finalysis.analytics.MerchantTotalsResponse.MerchantTotal;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.time.YearMonth;
import java.util.ArrayList;
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
 * The drill-down on the sample data, imported for real with the answer key's categories: every cell of the
 * categories breakdown (row × month, for the household and each account), every month's income and spending, and
 * every merchant's {@code spent} equal the rows the drill-down lists for them. The sample data has no hand-computed
 * cell file yet (step 4's oracle), so here the cells are the endpoints' own, and the test proves the drill-down and
 * the totals agree on every one.
 */
@IntegrationTest
@AutoConfigureMockMvc
class DrillDownSampleDataTest {

    private static final MonthRange SAMPLE = new MonthRange(YearMonth.of(2026, 3), YearMonth.of(2026, 10));

    private final ImportTestSupport support;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final DrillDownService drillDown;
    private final CategoryTotalsService categories;
    private final MonthlyTotalsService months;
    private final MerchantTotalsService merchants;

    DrillDownSampleDataTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper,
                            DrillDownService drillDown, CategoryTotalsService categories, MonthlyTotalsService months,
                            MerchantTotalsService merchants) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.drillDown = drillDown;
        this.categories = categories;
        this.months = months;
        this.merchants = merchants;
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @Test
    void everyTotalOfTheSampleDataDrillsDownToItsRows() throws Exception {
        assertThat(support.count("SELECT count(*) FROM statement")).isZero();
        SampleDataImport sample = new SampleDataImport(support, jsonMapper);
        Map<String, Long> accounts = sample.load();
        sample.applyAnswerKey(jdbc, accounts);

        List<Optional<Long>> scopes = new ArrayList<>();
        scopes.add(Optional.empty());
        accounts.values().forEach(id -> scopes.add(Optional.of(id)));
        List<String> problems = new ArrayList<>();
        int cells = 0;
        for (Optional<Long> scope : scopes) {
            CategoryTotalsResponse breakdown = categories.categories(Optional.of(SAMPLE.from()),
                    Optional.of(SAMPLE.to()), scope, Optional.empty(), false);
            for (CategoryTotalsResponse.Row row : breakdown.rows()) {
                for (CategoryTotalsResponse.Cell cell : row.months()) {
                    Filter filter = new Filter(new MonthRange(cell.month(), cell.month())).account(scope)
                            .bucket(row.bucket());
                    if (row.category() != null) {
                        filter = filter.category(row.category().id());
                    }
                    cells++;
                    compare(problems, scope + " " + row.bucket() + " " + row.category() + " " + cell.month(),
                            DrillDownTest.readAll(drillDown, filter), cell.flow());
                }
            }
            for (MonthTotals m : months.months(Optional.of(SAMPLE.from()), Optional.of(SAMPLE.to()), scope).months()) {
                Filter month = new Filter(new MonthRange(m.month(), m.month())).account(scope);
                cells += 2;
                if (DrillDownTest.readAll(drillDown, month.measure(TrendMeasure.SPENT)).net().compareTo(m.spending())
                        != 0) {
                    problems.add(scope + " " + m.month() + " spending");
                }
                if (DrillDownTest.readAll(drillDown, month.measure(TrendMeasure.RECEIVED)).net().compareTo(m.income())
                        != 0) {
                    problems.add(scope + " " + m.month() + " income");
                }
            }
        }
        for (MerchantTotal merchant : merchants.merchants(Optional.of(SAMPLE.from()), Optional.of(SAMPLE.to()),
                Optional.empty(), Optional.empty(), 50).items()) {
            cells++;
            MoneyFlow drilled = DrillDownTest.readAll(drillDown,
                    new Filter(SAMPLE).measure(TrendMeasure.SPENT).merchant(merchant.merchant()));
            if (drilled.net().negate().compareTo(merchant.spent()) != 0 || drilled.count() != merchant.count()) {
                problems.add("merchant " + merchant.merchant());
            }
        }
        assertThat(problems).isEmpty();
        assertThat(cells).as("cells compared, so the test can't pass by checking nothing").isEqualTo(318);
    }

    private static void compare(List<String> problems, String where, MoneyFlow drilled, MoneyFlow cell) {
        if (!drilled.equals(cell)) {
            problems.add(where + ": rows " + drilled + ", cell " + cell);
        }
    }
}
