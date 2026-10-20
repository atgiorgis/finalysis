package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture.Entry;
import com.finalysis.analytics.AnalyticsFixture.Loaded;
import com.finalysis.analytics.MerchantTotalsResponse.MerchantTotal;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The drill-down ({@code GET /api/analytics/transactions}) on the Phase 5 fixture: for every hand-computed cell, the
 * rows it lists, read page by page, add up to exactly that cell. The cells are {@code expected-month-totals.csv}'s
 * (month × bucket, for the household and each account) and, per category, {@code categories.csv}'s rows grouped by
 * month, bucket and category, summed here in plain Java. Neither is derived from the code under test.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DrillDownTest {

    /** Small, so most cells take several pages. */
    static final int PAGE = 3;

    private final MockMvc mockMvc;
    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final JdbcTemplate jdbc;
    private final DrillDownService drillDown;
    private final MerchantTotalsService merchants;
    private Loaded loaded;

    DrillDownTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper,
                  DrillDownService drillDown, MerchantTotalsService merchants) {
        this.mockMvc = mockMvc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, jsonMapper);
        this.jdbc = jdbc;
        this.drillDown = drillDown;
        this.merchants = merchants;
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

    /** Every money and count cell of {@code expected-month-totals.csv}: 15 rows × 7 cells. */
    @Test
    void everyMonthCellDrillsDownToItsHandComputedTotal() {
        List<String> problems = new ArrayList<>();
        int cells = 0;
        for (String line : AnalyticsFixture.expectedMonthTotalLines()) {
            String[] c = line.split(",", -1);
            Optional<Long> account = c[0].equals("ALL") ? Optional.empty() : Optional.of(loaded.accounts().get(c[0]));
            YearMonth month = YearMonth.parse(c[1]);
            String where = c[0] + " " + c[1];
            cells += 7;
            check(problems, where + " income", rows(filter(month, account).measure(TrendMeasure.RECEIVED)).net(),
                    c[3]);
            check(problems, where + " spending", rows(filter(month, account).measure(TrendMeasure.SPENT)).net(),
                    c[4]);
            check(problems, where + " refunds", rows(filter(month, account).bucket(TxnBucket.REFUND)), c[7], c[8], c[9]);
            check(problems, where + " uncategorized", rows(filter(month, account).bucket(TxnBucket.UNCATEGORIZED)),
                    c[10], c[11], c[12]);
            check(problems, where + " unlinked", rows(filter(month, account).bucket(TxnBucket.UNLINKED_TRANSFER)),
                    c[14], c[15], c[16]);
            check(problems, where + " linked", rows(filter(month, account).bucket(TxnBucket.LINKED_TRANSFER)),
                    c[17], c[18], c[19]);
            MoneyFlow all = rows(filter(month, account));
            check(problems, where + " every txn", all.net(), c[21]);
            if (all.count() != Integer.parseInt(c[20])) {
                problems.add(where + " txn count: " + all.count() + ", expected " + c[20]);
            }
        }
        assertThat(problems).isEmpty();
        assertThat(cells).isEqualTo(105);
    }

    /** Every month × bucket × category of {@code categories.csv}, for the household and each account. */
    @Test
    void everyCategoryCellDrillsDownToTheKeysRows() {
        Map<String, BigDecimal[]> expected = new TreeMap<>();
        for (Entry entry : AnalyticsFixture.entries()) {
            if (entry.category() == null) {
                continue; // the uncategorized cells are the month file's
            }
            String account = entry.file().startsWith(AnalyticsFixture.CHECKING) ? AnalyticsFixture.CHECKING
                    : AnalyticsFixture.CARD;
            for (String scope : List.of("ALL", account)) {
                String key = String.join("|", scope, YearMonth.from(entry.date()).toString(),
                        entry.expectedBucket().name(), entry.category());
                BigDecimal[] sum = expected.computeIfAbsent(key, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                sum[0] = sum[0].add(entry.amount());
                sum[1] = sum[1].add(BigDecimal.ONE);
            }
        }

        List<String> problems = new ArrayList<>();
        expected.forEach((key, sum) -> {
            String[] k = key.split("\\|");
            Optional<Long> account = k[0].equals("ALL") ? Optional.empty() : Optional.of(loaded.accounts().get(k[0]));
            MoneyFlow drilled = rows(filter(YearMonth.parse(k[1]), account).bucket(TxnBucket.valueOf(k[2]))
                    .category(categoryId(k[3])));
            if (drilled.net().compareTo(sum[0]) != 0 || drilled.count() != sum[1].intValue()) {
                problems.add(key + ": " + drilled.net() + " in " + drilled.count() + ", expected " + sum[0] + " in "
                        + sum[1]);
            }
        });
        assertThat(problems).isEmpty();
        assertThat(expected).as("cells compared").hasSize(80);
    }

    /** Each merchant's {@code spent} is its spending rows, December to April. */
    @Test
    void everyMerchantDrillsDownToItsSpent() {
        MonthRange range = new MonthRange(YearMonth.of(2025, 12), YearMonth.of(2026, 4));
        List<MerchantTotal> all = merchants.merchants(Optional.of(range.from()), Optional.of(range.to()),
                Optional.empty(), Optional.empty(), 50).items();

        assertThat(all).isNotEmpty().allSatisfy(merchant -> {
            MoneyFlow drilled = rows(new Filter(range).measure(TrendMeasure.SPENT).merchant(merchant.merchant()));
            assertThat(drilled.net().negate()).as(merchant.merchant()).isEqualTo(merchant.spent());
            assertThat(drilled.count()).as(merchant.merchant()).isEqualTo(merchant.count());
        });
    }

    /** Rows without a merchant are the merchants endpoint's null row, and {@code noMerchant} drills into it. */
    @Test
    void theNullMerchantRowDrillsDown() {
        long mystery = loaded.txns().get("fixture-checking_2026-02.csv:6");
        String merchant = jdbc.queryForObject("SELECT merchant FROM txn WHERE id = ?", String.class, mystery);
        jdbc.update("UPDATE txn SET merchant = NULL WHERE id = ?", mystery);
        try {
            List<MerchantTotal> all = merchants.merchants(Optional.of(YearMonth.of(2026, 2)),
                    Optional.of(YearMonth.of(2026, 2)), Optional.empty(), Optional.empty(), 50).items();
            MerchantTotal none = all.stream().filter(m -> m.merchant() == null).findFirst().orElseThrow();
            assertThat(none.spent()).isEqualTo(new BigDecimal("60.00"));

            MoneyFlow drilled = rows(filter(YearMonth.of(2026, 2), Optional.empty()).measure(TrendMeasure.SPENT)
                    .withoutMerchant());
            assertThat(drilled).isEqualTo(new MoneyFlow(new BigDecimal("-60.00"), new BigDecimal("0.00"),
                    new BigDecimal("-60.00"), 1));
        } finally {
            jdbc.update("UPDATE txn SET merchant = ? WHERE id = ?", merchant, mystery);
        }
    }

    /** The rows carry their bucket, newest first; {@code total} is over every page. */
    @Test
    void theApiListsRowsWithTheirBucketNewestFirst() throws Exception {
        mockMvc.perform(get("/api/analytics/transactions").param("month", "2026-02").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range.from").value("2026-02"))
                .andExpect(jsonPath("$.range.to").value("2026-02"))
                .andExpect(jsonPath("$.total.net").value("1063.27"))
                .andExpect(jsonPath("$.total.count").value(14))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalPages").value(7))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].txnDate").value("2026-02-27"))
                .andExpect(jsonPath("$.items[0].amount").value("-45.00"))
                .andExpect(jsonPath("$.items[0].bucket").value("EXPENSE"))
                .andExpect(jsonPath("$.items[0].category.name").value("Dining"))
                .andExpect(jsonPath("$.items[0].merchant").value("FIXTURE DINER"))
                .andExpect(jsonPath("$.items[0].rawDescription").doesNotExist());
        mockMvc.perform(get("/api/analytics/transactions").param("month", "2026-02")
                        .param("bucket", "UNCATEGORIZED"))
                .andExpect(jsonPath("$.items[*].bucket").value(everyItem(is("UNCATEGORIZED"))))
                .andExpect(jsonPath("$.items[0].category").isEmpty())
                .andExpect(jsonPath("$.total.moneyOut").value("-68.00"));
    }

    @Test
    void invalidParametersAre400s() throws Exception {
        String path = "/api/analytics/transactions";
        mockMvc.perform(get(path).param("month", "2026-02").param("from", "2026-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("month"));
        mockMvc.perform(get(path).param("bucket", "UNCATEGORIZED").param("categoryId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));
        mockMvc.perform(get(path).param("merchant", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("merchant"));
        mockMvc.perform(get(path).param("merchant", "TARGET").param("noMerchant", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("noMerchant"));
        mockMvc.perform(get(path).param("size", "201").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        mockMvc.perform(get(path).param("size", "200")).andExpect(status().isOk());
        mockMvc.perform(get(path).param("bucket", "SAVINGS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("bucket"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("LINKED_TRANSFER")))
                .andExpect(content().string(not(containsString("SAVINGS"))));
        mockMvc.perform(get(path).param("measure", "LOST")).andExpect(status().isBadRequest());
        mockMvc.perform(get(path).param("from", "2024-04").param("to", "2026-04"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("to"));
        long unknown = jdbc.queryForObject("SELECT max(id) + 1000 FROM account", Long.class);
        mockMvc.perform(get(path).param("accountId", "" + unknown)).andExpect(status().isNotFound());
        long unknownCategory = jdbc.queryForObject("SELECT max(id) + 1000 FROM category", Long.class);
        mockMvc.perform(get(path).param("categoryId", "" + unknownCategory)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private Filter filter(YearMonth month, Optional<Long> account) {
        return new Filter(new MonthRange(month, month)).account(account);
    }

    /** Reads every page and adds the rows up; also checks that they match the response's {@code total}. */
    MoneyFlow rows(Filter filter) {
        return readAll(drillDown, filter);
    }

    static MoneyFlow readAll(DrillDownService drillDown, Filter filter) {
        BigDecimal out = new BigDecimal("0.00");
        BigDecimal in = new BigDecimal("0.00");
        int count = 0;
        DrillDownResponse page;
        int index = 0;
        do {
            page = drillDown.transactions(filter.build(index++));
            for (DrillDownResponse.Item item : page.items()) {
                if (item.amount().signum() < 0) {
                    out = out.add(item.amount());
                } else {
                    in = in.add(item.amount());
                }
                count++;
            }
        } while (index < page.totalPages());
        MoneyFlow rows = new MoneyFlow(out, in, out.add(in), count);
        assertThat(rows).as("the rows and the response's total").isEqualTo(page.total());
        return rows;
    }

    private long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM category WHERE name = ?", Long.class, name);
    }

    private static void check(List<String> problems, String what, BigDecimal actual, String expected) {
        if (actual.compareTo(new BigDecimal(expected)) != 0) {
            problems.add(what + ": " + actual + ", expected " + expected);
        }
    }

    private static void check(List<String> problems, String what, MoneyFlow actual, String out, String in,
                              String count) {
        check(problems, what + " out", actual.moneyOut(), out);
        check(problems, what + " in", actual.moneyIn(), in);
        if (actual.count() != Integer.parseInt(count)) {
            problems.add(what + " count: " + actual.count() + ", expected " + count);
        }
    }

    /** A drill-down filter under construction, paged by {@link #PAGE}. */
    record Filter(MonthRange range, Optional<Long> account, Optional<TxnBucket> bucket,
                  Optional<TrendMeasure> measure, Optional<Long> category, Optional<String> merchant,
                  boolean noMerchant) {

        Filter(MonthRange range) {
            this(range, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    false);
        }

        Filter account(Optional<Long> id) {
            return new Filter(range, id, bucket, measure, category, merchant, noMerchant);
        }

        Filter bucket(TxnBucket b) {
            return new Filter(range, account, Optional.of(b), measure, category, merchant, noMerchant);
        }

        Filter measure(TrendMeasure m) {
            return new Filter(range, account, bucket, Optional.of(m), category, merchant, noMerchant);
        }

        Filter category(long id) {
            return new Filter(range, account, bucket, measure, Optional.of(id), merchant, noMerchant);
        }

        Filter merchant(String name) {
            return name == null ? withoutMerchant()
                    : new Filter(range, account, bucket, measure, category, Optional.of(name), false);
        }

        Filter withoutMerchant() {
            return new Filter(range, account, bucket, measure, category, Optional.empty(), true);
        }

        DrillDownFilter build(int page) {
            return new DrillDownFilter(Optional.empty(), Optional.of(range.from()), Optional.of(range.to()), bucket,
                    measure, account, category, merchant, noMerchant, page, PAGE);
        }
    }
}
