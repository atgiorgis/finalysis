package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture.Loaded;
import com.finalysis.analytics.CategoryTotalsResponse.Row;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Monthly totals on the Phase 5 fixture (ANA-01, ANA-02; docs/plans/phase-5.md §1, §9.1). The expected figures are
 * the hand-computed {@code expected-month-totals.csv} and the plan's worked examples, written here as literals; none
 * is derived from the code under test.
 *
 * <p>The fixture is imported once for the class; no test here changes it.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonthlyTotalsTest {

    static final Optional<YearMonth> DEC = Optional.of(YearMonth.of(2025, 12));
    static final Optional<YearMonth> APR = Optional.of(YearMonth.of(2026, 4));
    static final YearMonth JAN = YearMonth.of(2026, 1);
    static final YearMonth FEB = YearMonth.of(2026, 2);
    static final YearMonth MAR = YearMonth.of(2026, 3);

    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final MonthlyTotalsService months;
    private final AccountTotalsService accounts;
    private final CategoryTotalsService categories;
    private final JdbcTemplate jdbc;
    private Loaded loaded;

    MonthlyTotalsTest(MockMvc mockMvc, AccountRepository accountRepository, JdbcTemplate jdbc, JsonMapper jsonMapper,
                      MonthlyTotalsService months, AccountTotalsService accounts, CategoryTotalsService categories) {
        this.support = new ImportTestSupport(mockMvc, accountRepository, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, jsonMapper);
        this.months = months;
        this.accounts = accounts;
        this.categories = categories;
        this.jdbc = jdbc;
    }

    @BeforeAll
    void importFixture() throws Exception {
        assertThat(support.count("SELECT count(*) FROM statement"))
                .as("household totals and coverage cover every account, so the database must start empty").isZero();
        loaded = fixture.load();
    }

    @AfterAll
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    /** Every column of every row of the hand-computed file: the household and each account, December to April. */
    @Test
    void everyMonthMatchesTheHandComputedTotals() {
        List<String> actual = new ArrayList<>();
        months.months(DEC, APR, Optional.empty()).months().forEach(m -> actual.add(line("ALL", m)));
        for (String slug : List.of(AnalyticsFixture.CHECKING, AnalyticsFixture.CARD)) {
            months.months(DEC, APR, Optional.of(account(slug))).months().forEach(m -> actual.add(line(slug, m)));
        }

        assertThat(actual).containsExactlyElementsOf(AnalyticsFixture.expectedMonthTotalLines());
    }

    /** The accounts endpoint and the account filter are the same computation, so they must agree exactly. */
    @Test
    void theAccountsBreakdownEqualsEachAccountsOwnTotals() {
        AccountTotalsResponse response = accounts.accounts(DEC, APR);

        assertThat(response.accounts()).extracting(AccountTotalsResponse.AccountTotals::accountId)
                .containsExactly(account(AnalyticsFixture.CHECKING), account(AnalyticsFixture.CARD));
        for (AccountTotalsResponse.AccountTotals item : response.accounts()) {
            assertThat(item.months()).isEqualTo(months.months(DEC, APR, Optional.of(item.accountId())).months());
        }
        assertThat(response.accounts().getFirst().name()).isEqualTo("Fixture Checking");
    }

    /** §9.1's hand-computed February, as the plan writes it. */
    @Test
    void februaryIsThePlansWorkedExample() {
        MonthTotals feb = month(FEB);

        assertThat(feb.income()).isEqualTo(money("3001.25"));
        assertThat(feb.spending()).isEqualTo(money("-1687.98"));
        assertThat(feb.net()).isEqualTo(money("1313.27"));
        assertThat(feb.savingsRatePercent()).isEqualTo(new BigDecimal("43.8"));
        assertThat(feb.unlinkedTransfers().net()).isEqualTo(money("-250.00"));
        assertThat(feb.linkedTransfers().net()).isEqualTo(money("0.00"));
        assertThat(feb.netChange()).as("the sum of all fourteen amounts").isEqualTo(money("1063.27"));
        assertThat(feb.txnCount()).isEqualTo(14);
    }

    /**
     * §1.2: the Target refund nets in Shopping, in its own month; January isn't restated. January's Shopping also has
     * the bookshop's −25.00 (card 01-18), which the plan's table leaves out.
     */
    @Test
    void aRefundInAnExpenseCategoryNetsInItsOwnMonth() {
        Row shopping = row(categories(), TxnBucket.EXPENSE, "Shopping");

        assertThat(cell(shopping, JAN)).isEqualTo(flow("-145.00", "0.00", 2));
        assertThat(cell(shopping, FEB)).isEqualTo(flow("0.00", "120.00", 1));
        assertThat(month(FEB).spending()).as("120.00 lower than February's purchases alone")
                .isEqualTo(money("-1807.98").add(money("120.00")));
    }

    /** §1.2, decision 3: Refunds &amp; Rebates reduces spending, on its own line, and is never income. */
    @Test
    void refundsAndRebatesReduceSpendingNotIncome() {
        MonthTotals mar = month(MAR);

        assertThat(mar.refunds()).isEqualTo(flow("0.00", "15.00", 1));
        assertThat(mar.income()).as("payroll 3000.00 and interest 1.30 only").isEqualTo(money("3001.30"));
        assertThat(mar.spending()).isEqualTo(money("-1809.48"));
        Row refunds = row(categories(), TxnBucket.REFUND, "Refunds & Rebates");
        assertThat(cell(refunds, MAR)).isEqualTo(flow("0.00", "15.00", 1));
        assertThat(rows(categories(), TxnBucket.INCOME)).extracting(r -> r.category().name())
                .doesNotContain("Refunds & Rebates");
    }

    /**
     * Phase 6 step 8b: the range's total spending is the month totals' spending, and each spending row's share of it
     * is computed here (half-even, one decimal), over every category even when one category is asked for. By hand for
     * March: spending −1,809.48; Shopping −63.00 → 3.5%; Refunds &amp; Rebates +15.00 → −0.8% (it lowers spending).
     */
    @Test
    void eachSpendingRowHasItsShareOfTheRangesSpending() {
        Optional<YearMonth> march = Optional.of(MAR);
        CategoryTotalsResponse all = categories.categories(march, march, Optional.empty(), Optional.empty(), false);

        assertThat(all.spending()).isEqualTo(money("-1809.48")).isEqualTo(month(MAR).spending());
        assertThat(row(all, TxnBucket.EXPENSE, "Shopping").spendingSharePercent()).isEqualByComparingTo("3.5");
        assertThat(row(all, TxnBucket.REFUND, "Refunds & Rebates").spendingSharePercent())
                .isEqualByComparingTo("-0.8");
        assertThat(rows(all, TxnBucket.INCOME)).allSatisfy(r -> assertThat(r.spendingSharePercent()).isNull());
        assertThat(rows(all, TxnBucket.LINKED_TRANSFER)).allSatisfy(r -> assertThat(r.spendingSharePercent()).isNull());

        long shopping = jdbc.queryForObject("SELECT id FROM category WHERE name = 'Shopping'", Long.class);
        CategoryTotalsResponse one = categories.categories(march, march, Optional.empty(), Optional.of(shopping),
                false);
        assertThat(one.spending()).as("the base is all spending, not the category's").isEqualTo(money("-1809.48"));
        assertThat(row(one, TxnBucket.EXPENSE, "Shopping").spendingSharePercent()).isEqualByComparingTo("3.5");

        CategoryTotalsResponse range = categories();
        BigDecimal spending = range.range().months().stream().map(m -> month(m).spending())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(range.spending()).as("a range's spending is its months' spending added up")
                .isEqualByComparingTo(spending);
    }

    /** §1.3: both uncategorized txns count in spending; one of them waits in the review queue. */
    @Test
    void uncategorizedTxnsCountBySignWithTheirReviewPendingCount() {
        MonthTotals feb = month(FEB);

        assertThat(feb.uncategorized()).isEqualTo(flow("-68.00", "0.00", 2));
        assertThat(feb.dataQuality().reviewPendingCount()).isEqualTo(1);
        Row uncategorized = row(categories(), TxnBucket.UNCATEGORIZED, null);
        assertThat(cellOf(uncategorized, FEB).flow()).isEqualTo(flow("-68.00", "0.00", 2));
        assertThat(cellOf(uncategorized, FEB).reviewPending()).isEqualTo(1);
        assertThat(cellOf(uncategorized, JAN).reviewPending()).isZero();
        assertThat(cellOf(row(categories(), TxnBucket.EXPENSE, "Dining"), FEB).reviewPending())
                .as("only the Uncategorized row carries it").isNull();
    }

    /** §1.4: a Transfer-category txn with no link is in neither total, on its own line under its category. */
    @Test
    void anUnlinkedTransferIsInNoTotal() {
        MonthTotals feb = month(FEB);

        assertThat(feb.unlinkedTransfers()).isEqualTo(flow("-250.00", "0.00", 1));
        assertThat(feb.dataQuality().unlinkedTransferCount()).isEqualTo(1);
        assertThat(cell(row(categories(), TxnBucket.UNLINKED_TRANSFER, "Transfer"), FEB))
                .isEqualTo(flow("-250.00", "0.00", 1));
    }

    /** §1.4: the card payment, both sides linked, nets to 0.00 for the household and is in no total. */
    @Test
    void aLinkedTransferIsInNoTotalAndOneRowWhateverItsCategory() {
        assertThat(month(FEB).linkedTransfers()).isEqualTo(flow("-400.00", "400.00", 2));

        Row linked = row(categories(), TxnBucket.LINKED_TRANSFER, null);
        assertThat(cell(linked, MAR)).as("the card side's Shopping category doesn't move it to Shopping")
                .isEqualTo(flow("-500.00", "500.00", 2));
        assertThat(cell(row(categories(), TxnBucket.EXPENSE, "Shopping"), MAR))
                .as("bookshop 03-03 and 03-25 only").isEqualTo(flow("-63.00", "0.00", 2));
        assertThat(rows(categories(), TxnBucket.LINKED_TRANSFER)).hasSize(1);
    }

    /** §9.1: marked not a transfer with an expense category, it counts as that expense. */
    @Test
    void aNotTransferTxnWithAnExpenseCategoryCountsAsSpending() {
        assertThat(cell(row(categories(), TxnBucket.EXPENSE, "Person-to-Person Out"), FEB))
                .isEqualTo(flow("-100.00", "0.00", 1));
    }

    /** §1.4: marked not a transfer with a USER Transfer category, it counts by sign under "Transfer". */
    @Test
    void aNotTransferTxnWithAUserTransferCategoryCountsBySign() {
        CategoryTotalsResponse response = categories();

        assertThat(cell(row(response, TxnBucket.EXPENSE, "Transfer"), MAR)).isEqualTo(flow("-35.00", "0.00", 1));
        assertThat(cell(row(response, TxnBucket.UNLINKED_TRANSFER, "Transfer"), MAR)).isEqualTo(MoneyFlow.ZERO);
        assertThat(month(MAR).unlinkedTransfers().count()).isZero();
    }

    /** §1.5: the 02-27 diner charge arrives with the March card statement and counts in February. */
    @Test
    void theMonthIsTheTxnDatesNotTheStatements() {
        Row dining = row(categories(), TxnBucket.EXPENSE, "Dining");

        assertThat(cell(dining, FEB)).isEqualTo(flow("-45.00", "0.00", 1));
        assertThat(cell(dining, JAN)).as("01-10 and 01-24").isEqualTo(flow("-105.00", "0.00", 2));
    }

    /** A category filter keeps that category's txns in every bucket they land in, the linked one included. */
    @Test
    void theCategoryFilterKeepsTheCategorysTxnsInEveryBucket() {
        long shopping = jdbc.queryForObject("SELECT id FROM category WHERE name = 'Shopping'", Long.class);
        CategoryTotalsResponse response = categories.categories(DEC, APR, Optional.empty(), Optional.of(shopping),
                false);

        assertThat(response.rows()).extracting(Row::bucket)
                .containsExactly(TxnBucket.EXPENSE, TxnBucket.LINKED_TRANSFER);
        assertThat(cell(row(response, TxnBucket.LINKED_TRANSFER, null), MAR)).isEqualTo(flow("0.00", "500.00", 1));
    }

    @Test
    void theUncategorizedFilterKeepsOnlyTheUncategorizedRow() {
        CategoryTotalsResponse response = categories.categories(DEC, APR, Optional.empty(), Optional.empty(), true);

        assertThat(response.rows()).extracting(Row::bucket).containsExactly(TxnBucket.UNCATEGORIZED);
        assertThat(response.rows().getFirst().total())
                .as("Feb −60.00 and −8.00, Apr +25.00; the linked uncategorized card payment is not here")
                .isEqualTo(flow("-68.00", "25.00", 3));
    }

    /** Rows come income first, then spending, refunds, uncategorized and the two transfer lines; rows by name. */
    @Test
    void rowsComeInBucketOrderThenByName() {
        List<String> order = categories().rows().stream()
                .map(r -> r.bucket() + ":" + (r.category() == null ? "-" : r.category().name())).toList();

        assertThat(order).containsExactly(
                "INCOME:Income", "INCOME:Interest",
                "EXPENSE:Dining", "EXPENSE:Groceries", "EXPENSE:Housing", "EXPENSE:Person-to-Person Out",
                "EXPENSE:Shopping", "EXPENSE:Subscriptions", "EXPENSE:Transfer",
                "REFUND:Refunds & Rebates",
                "UNCATEGORIZED:-",
                "UNLINKED_TRANSFER:Transfer",
                "LINKED_TRANSFER:-");
    }

    // ------------------------------------------------------------------ helpers

    private long account(String slug) {
        return loaded.accounts().get(slug);
    }

    private MonthTotals month(YearMonth month) {
        return months.months(Optional.of(month), Optional.of(month), Optional.empty()).months().getFirst();
    }

    private CategoryTotalsResponse categories() {
        return categories.categories(DEC, APR, Optional.empty(), Optional.empty(), false);
    }

    static Row row(CategoryTotalsResponse response, TxnBucket bucket, String category) {
        List<Row> matching = response.rows().stream()
                .filter(r -> r.bucket() == bucket)
                .filter(r -> category == null ? r.category() == null
                        : r.category() != null && r.category().name().equals(category))
                .toList();
        assertThat(matching).as(bucket + " " + category).hasSize(1);
        return matching.getFirst();
    }

    static List<Row> rows(CategoryTotalsResponse response, TxnBucket bucket) {
        return response.rows().stream().filter(r -> r.bucket() == bucket).toList();
    }

    static MoneyFlow cell(Row row, YearMonth month) {
        return cellOf(row, month).flow();
    }

    static CategoryTotalsResponse.Cell cellOf(Row row, YearMonth month) {
        return row.months().stream().filter(c -> c.month().equals(month)).findFirst().orElseThrow();
    }

    static MoneyFlow flow(String moneyOut, String moneyIn, int count) {
        BigDecimal out = money(moneyOut);
        BigDecimal in = money(moneyIn);
        return new MoneyFlow(out, in, out.add(in), count);
    }

    static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    /** The totals as a line of {@code expected-month-totals.csv}: same columns, same formatting. */
    private String line(String scope, MonthTotals m) {
        Map<Long, String> slugs = loaded.accounts().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        DataQuality q = m.dataQuality();
        String flags = q.flags().stream().map(f -> f.type() + ":" + slugs.get(f.accountId()))
                .collect(Collectors.joining(";"));
        return String.join(",", scope, m.month().toString(), m.coverage().name(),
                plain(m.income()), plain(m.spending()), plain(m.net()), plain(m.savingsRatePercent()),
                plain(m.refunds().moneyOut()), plain(m.refunds().moneyIn()), "" + m.refunds().count(),
                plain(m.uncategorized().moneyOut()), plain(m.uncategorized().moneyIn()),
                "" + m.uncategorized().count(), "" + q.reviewPendingCount(),
                plain(m.unlinkedTransfers().moneyOut()), plain(m.unlinkedTransfers().moneyIn()),
                "" + m.unlinkedTransfers().count(),
                plain(m.linkedTransfers().moneyOut()), plain(m.linkedTransfers().moneyIn()),
                "" + m.linkedTransfers().count(),
                "" + m.txnCount(), plain(m.netChange()), "" + q.countedTxns(),
                plain(q.uncategorizedPercentOfCount()), plain(q.reviewPendingPercentOfCount()),
                plain(q.uncategorizedPercentOfSpending()), flags);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
