package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture.Loaded;
import com.finalysis.analytics.MerchantTotalsResponse.MerchantTotal;
import com.finalysis.api.InvalidRequestException;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
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
 * Merchant totals (ANA-01, docs/plans/phase-5.md §5.2) on the Phase 5 fixture, December to April. Figures
 * hand-computed from {@code categories.csv}'s spending rows; their total, 7857.43, is −(the sum of
 * {@code expected-month-totals.csv}'s five household {@code spending} cells).
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantTotalsTest {

    private static final Optional<YearMonth> DEC = Optional.of(YearMonth.of(2025, 12));
    private static final Optional<YearMonth> APR = Optional.of(YearMonth.of(2026, 4));

    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final JdbcTemplate jdbc;
    private final MerchantTotalsService merchants;
    private Loaded loaded;

    MerchantTotalsTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper,
                       MerchantTotalsService merchants) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, jsonMapper);
        this.jdbc = jdbc;
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

    /**
     * Rent 4 × 1500.00; the electronics purchase 700.00; the diner's eight charges 40 + 50 + 55 + 45 + 50 + 60 + 95 + 70
     * = 465.00. The other 22 spending rows are 692.43, so the list adds up to 7857.43 over 35 rows.
     */
    @Test
    void theTopMerchantsAndTheRestAddUpToSpending() {
        MerchantTotalsResponse response = merchants.merchants(DEC, APR, Optional.empty(), Optional.empty(), 3);

        assertThat(response.items()).extracting(MerchantTotal::merchant)
                .containsExactly("FIXTURE PROPERTY RENT", "FIXTURE ELECTRONICS", "FIXTURE DINER");
        assertThat(response.items()).extracting(MerchantTotal::spent)
                .containsExactly(money("6000.00"), money("700.00"), money("465.00"));
        assertThat(response.items()).extracting(MerchantTotal::count).containsExactly(4, 1, 8);
        assertThat(response.othersSpent()).isEqualTo(money("692.43"));
        assertThat(response.othersCount()).isEqualTo(22);
        assertThat(response.totalSpent()).isEqualTo(money("7857.43"));
        assertThat(response.totalCount()).isEqualTo(35);

        MerchantTotal diner = response.items().get(2);
        assertThat(diner.firstDate()).isEqualTo(LocalDate.parse("2025-12-18"));
        assertThat(diner.lastDate()).isEqualTo(LocalDate.parse("2026-04-08"));
        assertThat(diner.category().name()).isEqualTo("Dining");
    }

    /**
     * The Target purchase and its refund net to 0.00 (§1.2); the Refunds &amp; Rebates credit is money back, so it is
     * last, negative. Uncategorized money out counts; the uncategorized credit (+25.00) is income, not here.
     */
    @Test
    void refundsNetAndUncategorizedMoneyOutCounts() {
        List<MerchantTotal> all = merchants.merchants(DEC, APR, Optional.empty(), Optional.empty(), 50).items();

        assertThat(all).hasSize(13);
        assertThat(merchant(all, "TARGET").spent()).isEqualTo(money("0.00"));
        assertThat(merchant(all, "TARGET").count()).isEqualTo(2);
        assertThat(all.getLast().merchant()).isEqualTo("ONLINE RETURN CREDIT");
        assertThat(all.getLast().spent()).isEqualTo(money("-15.00"));
        assertThat(all.getLast().category().name()).isEqualTo("Refunds & Rebates");
        assertThat(merchant(all, "MYSTERY PAYMENT").spent()).isEqualTo(money("60.00"));
        assertThat(merchant(all, "MYSTERY PAYMENT").category()).as("uncategorized").isNull();
        assertThat(all).extracting(MerchantTotal::merchant).doesNotContain("FIXTURE MISC CREDIT", "PAYMENT THANK YOU",
                "FIXTURE EMPLOYER PAYROLL", "OUTSIDE CARD AUTOPAY");
        assertThat(all.stream().map(MerchantTotal::spent).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualTo(money("7857.43"));
    }

    @Test
    void filtersByAccountAndCategory() {
        long shopping = jdbc.queryForObject("SELECT id FROM category WHERE name = 'Shopping'", Long.class);
        MerchantTotalsResponse byCategory = merchants.merchants(DEC, APR, Optional.empty(), Optional.of(shopping), 10);

        assertThat(byCategory.items()).extracting(MerchantTotal::merchant)
                .containsExactly("FIXTURE ELECTRONICS", "FIXTURE BOOKSHOP", "TARGET");
        assertThat(byCategory.totalSpent()).as("700.00 + 118.00 + 0.00").isEqualTo(money("818.00"));

        MerchantTotalsResponse checking = merchants.merchants(DEC, APR,
                Optional.of(loaded.accounts().get(AnalyticsFixture.CHECKING)), Optional.empty(), 50);
        assertThat(checking.totalSpent()).as("−(checking's five spending cells)").isEqualTo(money("6293.00"));
    }

    @Test
    void theLimitIsBounded() {
        assertThatThrownBy(() -> merchants.merchants(DEC, APR, Optional.empty(), Optional.empty(), 0))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> merchants.merchants(DEC, APR, Optional.empty(), Optional.empty(), 51))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(merchants.merchants(DEC, APR, Optional.empty(), Optional.empty(), 50).limit()).isEqualTo(50);
    }

    private static MerchantTotal merchant(List<MerchantTotal> all, String name) {
        return all.stream().filter(m -> name.equals(m.merchant())).findFirst().orElseThrow();
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
