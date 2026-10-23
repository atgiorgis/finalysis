package com.finalysis.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.analytics.CategoryKind;
import com.finalysis.analytics.CategoryRef;
import com.finalysis.analytics.CategoryTotalsResponse;
import com.finalysis.analytics.CategoryTotalsResponse.Row;
import com.finalysis.analytics.MoneyFlow;
import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.TxnBucket;
import com.finalysis.dashboard.DashboardResponse.Slice;
import com.finalysis.dashboard.DashboardResponse.SliceKind;
import com.finalysis.dashboard.DashboardResponse.SpendingSlices;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The donut's rule (docs/plans/phase-7.md §3.1, §13 decision 7), on hand-built category rows: the six expense
 * categories with the most spending, then Other, then Uncategorized money out; the slices add up to spending.
 */
class DashboardSlicesTest {

    private static final YearMonth MARCH = YearMonth.of(2026, 3);
    private static long nextId = 1;

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static Row row(TxnBucket bucket, String name, String out, String in, String share) {
        CategoryRef category = name == null ? null
                : new CategoryRef(nextId++, name, bucket == TxnBucket.EXPENSE ? CategoryKind.EXPENSE
                : CategoryKind.INCOME);
        BigDecimal moneyOut = money(out);
        BigDecimal moneyIn = money(in);
        return new Row(bucket, category, new MoneyFlow(moneyOut, moneyIn, moneyOut.add(moneyIn), 1), List.of(),
                share == null ? null : new BigDecimal(share));
    }

    private static SpendingSlices slices(String spending, Row... rows) {
        return DashboardService.slices(new CategoryTotalsResponse(new MonthRange(MARCH, MARCH), null, null, false,
                List.of(), List.of(rows), money(spending)));
    }

    private static List<String> names(SpendingSlices slices) {
        List<String> names = new ArrayList<>();
        for (Slice s : slices.rows()) {
            names.add(s.kind() == SliceKind.CATEGORY ? s.category().name() : s.kind().name());
        }
        return names;
    }

    private static BigDecimal sum(SpendingSlices slices) {
        return slices.rows().stream().map(Slice::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void sixLargestThenOtherThenUncategorized() {
        SpendingSlices s = slices("-1000.00",
                row(TxnBucket.INCOME, "Salary", "0.00", "3000.00", null),
                row(TxnBucket.EXPENSE, "Rent", "-500.00", "0.00", "50.0"),
                row(TxnBucket.EXPENSE, "Groceries", "-120.00", "0.00", "12.0"),
                row(TxnBucket.EXPENSE, "Dining", "-80.00", "0.00", "8.0"),
                row(TxnBucket.EXPENSE, "Fuel", "-60.00", "0.00", "6.0"),
                row(TxnBucket.EXPENSE, "Utilities", "-50.00", "0.00", "5.0"),
                row(TxnBucket.EXPENSE, "Shopping", "-40.00", "0.00", "4.0"),
                row(TxnBucket.EXPENSE, "Books", "-30.00", "0.00", "3.0"),
                row(TxnBucket.EXPENSE, "Pets", "-20.00", "0.00", "2.0"),
                row(TxnBucket.UNCATEGORIZED, null, "-100.00", "5.00", "10.0"),
                row(TxnBucket.LINKED_TRANSFER, null, "-700.00", "700.00", null));

        assertThat(names(s)).containsExactly("Rent", "Groceries", "Dining", "Fuel", "Utilities", "Shopping", "OTHER",
                "UNCATEGORIZED");
        Slice other = s.rows().get(6);
        assertThat(other.amount()).isEqualByComparingTo("-50.00");
        assertThat(other.spendingSharePercent()).isEqualByComparingTo("5.0");
        assertThat(other.members()).extracting(m -> m.category().name()).containsExactly("Books", "Pets");
        Slice uncategorized = s.rows().get(7);
        assertThat(uncategorized.amount()).as("money out only").isEqualByComparingTo("-100.00");
        assertThat(sum(s)).isEqualByComparingTo(s.spending());
    }

    @Test
    void refundsAboveChargesAndTheRefundsLineGoToOther() {
        SpendingSlices s = slices("-575.00",
                row(TxnBucket.EXPENSE, "Rent", "-500.00", "0.00", "87.0"),
                row(TxnBucket.EXPENSE, "Electronics", "-50.00", "120.00", "-12.2"),
                row(TxnBucket.EXPENSE, "Gifts", "-10.00", "10.00", "0.0"),
                row(TxnBucket.REFUND, "Refunds & Rebates", "0.00", "15.00", "-2.6"),
                row(TxnBucket.EXPENSE, "Dining", "-160.00", "0.00", "27.8"));

        assertThat(names(s)).as("no negative slice, no empty Uncategorized").containsExactly("Rent", "Dining", "OTHER");
        Slice other = s.rows().get(2);
        assertThat(other.members()).extracting(m -> m.category().name())
                .containsExactly("Gifts", "Refunds & Rebates", "Electronics");
        assertThat(other.amount()).isEqualByComparingTo("85.00");
        assertThat(other.spendingSharePercent()).isEqualByComparingTo("-14.8");
        assertThat(sum(s)).isEqualByComparingTo("-575.00").isEqualByComparingTo(s.spending());
    }

    @Test
    void sixOrFewerCategoriesHaveNoOther() {
        SpendingSlices s = slices("-30.00",
                row(TxnBucket.EXPENSE, "Dining", "-20.00", "0.00", "66.7"),
                row(TxnBucket.EXPENSE, "Coffee", "-10.00", "0.00", "33.3"));

        assertThat(names(s)).containsExactly("Dining", "Coffee");
    }

    @Test
    void equalSpendingIsOrderedByName() {
        SpendingSlices s = slices("-20.00",
                row(TxnBucket.EXPENSE, "Zoo", "-10.00", "0.00", "50.0"),
                row(TxnBucket.EXPENSE, "Art", "-10.00", "0.00", "50.0"));

        assertThat(names(s)).containsExactly("Art", "Zoo");
    }

    @Test
    void withoutSpendingSharesOthersShareIsNull() {
        SpendingSlices s = slices("0.00",
                row(TxnBucket.EXPENSE, "Gifts", "-10.00", "10.00", null));

        assertThat(s.rows()).singleElement().satisfies(other -> {
            assertThat(other.kind()).isEqualTo(SliceKind.OTHER);
            assertThat(other.spendingSharePercent()).isNull();
        });
    }

    @Test
    void noRowsNoSlices() {
        assertThat(slices("0.00").rows()).isEmpty();
    }
}
