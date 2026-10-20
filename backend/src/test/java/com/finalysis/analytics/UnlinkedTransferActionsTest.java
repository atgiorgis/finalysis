package com.finalysis.analytics;

import static com.finalysis.analytics.MonthlyTotalsTest.flow;
import static com.finalysis.analytics.MonthlyTotalsTest.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The CAT-08 actions on the fixture's unlinked transfer move it between buckets as docs/plans/phase-5.md §1.4's table
 * says. Checking 2026-02-10 {@code OUTSIDE CARD AUTOPAY} −250.00 starts in {@code unlinkedTransfers}; February's
 * spending starts at −1687.98 (§9.1). Each test imports the fixture afresh and takes one action through the API.
 *
 * <p>The table's first row (the other account is imported and detection links it) is the same bucket move as marking
 * it one-sided; detection itself is exercised by the four card payments the fixture links.
 */
@IntegrationTest
@AutoConfigureMockMvc
class UnlinkedTransferActionsTest {

    private static final YearMonth FEB = YearMonth.of(2026, 2);

    private final MockMvc mockMvc;
    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final JdbcTemplate jdbc;
    private final MonthlyTotalsService months;
    private long autopay;

    UnlinkedTransferActionsTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper,
                                MonthlyTotalsService months) {
        this.mockMvc = mockMvc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, jsonMapper);
        this.jdbc = jdbc;
        this.months = months;
    }

    @BeforeEach
    void importFixture() throws Exception {
        assertThat(support.count("SELECT count(*) FROM statement")).isZero();
        autopay = fixture.load().txns().get("fixture-checking_2026-02.csv:5");
        MonthTotals feb = february();
        assertThat(feb.unlinkedTransfers()).isEqualTo(flow("-250.00", "0.00", 1));
        assertThat(feb.spending()).isEqualTo(money("-1687.98"));
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @Test
    void markedOneSidedItBecomesALinkedTransfer() throws Exception {
        mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTxnId\":" + autopay + "}")).andExpect(status().isCreated());

        MonthTotals feb = february();
        assertThat(feb.unlinkedTransfers()).isEqualTo(MoneyFlow.ZERO);
        assertThat(feb.linkedTransfers()).as("the card payment's ±400.00 and the autopay")
                .isEqualTo(flow("-650.00", "400.00", 3));
        assertThat(feb.spending()).isEqualTo(money("-1687.98"));
    }

    @Test
    void markedNotATransferItIsUncategorizedAndCountsAsSpending() throws Exception {
        mockMvc.perform(post("/api/transactions/{id}/not-transfer", autopay)).andExpect(status().isOk());

        MonthTotals feb = february();
        assertThat(feb.unlinkedTransfers()).isEqualTo(MoneyFlow.ZERO);
        assertThat(feb.uncategorized()).isEqualTo(flow("-318.00", "0.00", 3));
        assertThat(feb.dataQuality().reviewPendingCount()).as("it waits as NOT_TRANSFER_UNCATEGORIZED").isEqualTo(2);
        assertThat(feb.spending()).isEqualTo(money("-1937.98"));
    }

    @Test
    void correctedToAnExpenseItCountsInThatCategory() throws Exception {
        long bankFees = jdbc.queryForObject("SELECT id FROM category WHERE name = 'Bank Fees'", Long.class);
        mockMvc.perform(put("/api/transactions/{id}/category", autopay).contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryId\":" + bankFees + ",\"applyToMerchant\":false}")).andExpect(status().isOk());

        MonthTotals feb = february();
        assertThat(feb.unlinkedTransfers()).isEqualTo(MoneyFlow.ZERO);
        assertThat(feb.uncategorized()).isEqualTo(flow("-68.00", "0.00", 2));
        assertThat(feb.spending()).isEqualTo(money("-1937.98"));
    }

    private MonthTotals february() {
        return months.months(Optional.of(FEB), Optional.of(FEB), Optional.empty()).months().getFirst();
    }
}
