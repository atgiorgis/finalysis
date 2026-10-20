package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.DrillDownTest.Filter;
import com.finalysis.analytics.MerchantTotalsResponse.MerchantTotal;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.time.YearMonth;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The drill-down on the sample data, imported for real with the answer key's categories: every cell of the
 * categories breakdown (row × month, for the household and each account), every month's income and spending, and
 * every merchant's {@code spent} equal the rows the drill-down lists for them. Each of the 318 cells is checked twice:
 * against the endpoint's own figure (step 3), and against {@link AnswerKeyOracle}'s (step 4), which computes it from
 * {@code answer-key.csv} without the app's code. A merchant's oracle figure groups the key's spending rows by the
 * merchant the import gave each one (the key has no cleaned merchant column).
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

        AnswerKeyOracle oracle = AnswerKeyOracle.answerKey();
        Map<AnswerKeyOracle.Key, AnswerKeyOracle.Sums> expected = oracle.cells();
        Map<Long, String> slugs = new HashMap<>();
        accounts.forEach((slug, id) -> slugs.put(id, slug));
        List<Optional<Long>> scopes = new ArrayList<>();
        scopes.add(Optional.empty());
        accounts.values().forEach(id -> scopes.add(Optional.of(id)));
        List<String> problems = new ArrayList<>();
        int cells = 0;
        int oracleCells = 0;
        for (Optional<Long> scope : scopes) {
            String scopeName = scope.map(slugs::get).orElse(AnswerKeyOracle.ALL);
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
                    MoneyFlow drilled = DrillDownTest.readAll(drillDown, filter);
                    String where = scopeName + " " + row.bucket() + " " + row.category() + " " + cell.month();
                    compare(problems, where, drilled, cell.flow());
                    AnswerKeyOracle.Sums want = expected.getOrDefault(new AnswerKeyOracle.Key(scopeName, cell.month(),
                            row.bucket().name(), row.category() == null ? null : row.category().name()),
                            AnswerKeyOracle.Sums.ZERO);
                    oracleCells++;
                    compare(problems, where + " (oracle)", drilled,
                            new MoneyFlow(want.moneyOut(), want.moneyIn(), want.net(), want.count()));
                }
            }
            for (MonthTotals m : months.months(Optional.of(SAMPLE.from()), Optional.of(SAMPLE.to()), scope).months()) {
                Filter month = new Filter(new MonthRange(m.month(), m.month())).account(scope);
                AnswerKeyOracle.Month want = oracle.month(scopeName, m.month());
                cells += 2;
                oracleCells += 2;
                BigDecimal spent = DrillDownTest.readAll(drillDown, month.measure(TrendMeasure.SPENT)).net();
                BigDecimal received = DrillDownTest.readAll(drillDown, month.measure(TrendMeasure.RECEIVED)).net();
                if (spent.compareTo(m.spending()) != 0 || spent.compareTo(want.spending()) != 0) {
                    problems.add(scopeName + " " + m.month() + " spending");
                }
                if (received.compareTo(m.income()) != 0 || received.compareTo(want.income()) != 0) {
                    problems.add(scopeName + " " + m.month() + " income");
                }
            }
        }
        Map<String, AnswerKeyOracle.Sums> byMerchant = oracleMerchants(oracle, accounts);
        for (MerchantTotal merchant : merchants.merchants(Optional.of(SAMPLE.from()), Optional.of(SAMPLE.to()),
                Optional.empty(), Optional.empty(), 50).items()) {
            cells++;
            oracleCells++;
            MoneyFlow drilled = DrillDownTest.readAll(drillDown,
                    new Filter(SAMPLE).measure(TrendMeasure.SPENT).merchant(merchant.merchant()));
            AnswerKeyOracle.Sums want = byMerchant.getOrDefault(merchant.merchant(), AnswerKeyOracle.Sums.ZERO);
            if (drilled.net().negate().compareTo(merchant.spent()) != 0 || drilled.count() != merchant.count()
                    || drilled.net().compareTo(want.net()) != 0 || drilled.count() != want.count()) {
                problems.add("merchant " + merchant.merchant());
            }
        }
        assertThat(merchants.merchants(Optional.of(SAMPLE.from()), Optional.of(SAMPLE.to()), Optional.empty(),
                Optional.empty(), 50).items()).as("every merchant listed, so all are drilled into")
                .hasSize(byMerchant.size());
        assertThat(problems).isEmpty();
        assertThat(oracleCells).as("every cell is also checked against the oracle").isEqualTo(cells);
        assertThat(cells).as("cells compared, so the test can't pass by checking nothing").isEqualTo(318);
    }

    /**
     * The key's spending rows grouped by the cleaned merchant the import gave each one, matched on account, date, raw
     * description and amount. Only the grouping comes from the database; the sums are the key's.
     */
    private Map<String, AnswerKeyOracle.Sums> oracleMerchants(AnswerKeyOracle oracle, Map<String, Long> accounts) {
        Map<String, List<AnswerKeyOracle.KeyRow>> rows = new TreeMap<>();
        for (AnswerKeyOracle.KeyRow row : oracle.rows()) {
            if (!row.bucket().equals("EXPENSE") && !row.bucket().equals("REFUND")) {
                continue;
            }
            String merchant = jdbc.queryForList("""
                    SELECT DISTINCT merchant FROM txn
                    WHERE account_id = ? AND txn_date = ? AND raw_description = ? AND amount = ?""", String.class,
                    accounts.get(row.account()), row.date(), row.description(), row.amount()).getFirst();
            rows.computeIfAbsent(merchant, m -> new ArrayList<>()).add(row);
        }
        Map<String, AnswerKeyOracle.Sums> sums = new TreeMap<>();
        rows.forEach((merchant, group) -> sums.put(merchant, AnswerKeyOracle.Sums.of(group)));
        return sums;
    }

    private static void compare(List<String> problems, String where, MoneyFlow drilled, MoneyFlow cell) {
        if (!drilled.equals(cell)) {
            problems.add(where + ": rows " + drilled + ", cell " + cell);
        }
    }
}
