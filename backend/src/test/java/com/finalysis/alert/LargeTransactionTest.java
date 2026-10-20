package com.finalysis.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Large transaction at its thresholds (docs/plans/phase-5.md §6.3, §6.6): one cent under, at and over each branch of
 * {@code max(500.00, 3 × median)}, the history's size and its day bounds, with the basis to the cent. The charge is on
 * 2026-04-15; its history window is 2025-10-17 to 2026-04-14. Rows written in SQL, rolled back.
 */
@IntegrationTest
@Transactional
class LargeTransactionTest {

    private static final LocalDate DAY = LocalDate.of(2026, 4, 15);
    private static final YearMonth APR = YearMonth.of(2026, 4);

    private final AlertRows rows;
    private final AlertQueryService alerts;
    private long shopping;
    private long account;

    LargeTransactionTest(JdbcTemplate jdbc, AlertQueryService alerts) {
        this.rows = new AlertRows(jdbc);
        this.alerts = alerts;
    }

    @BeforeEach
    void data() {
        shopping = rows.categoryId("Shopping");
        account = rows.account("7201", LocalDate.of(2025, 9, 1), LocalDate.of(2026, 4, 30));
    }

    /** History 100, 150, 200, 250, 300: median 200.00, threshold max(500.00, 600.00) = 600.00. */
    @ParameterizedTest
    @CsvSource({"599.99, false", "600.00, true", "600.01, true"})
    void theMedianBranch(String charge, boolean fires) {
        history(shopping, "100.00", "150.00", "200.00", "250.00", "300.00");
        long txn = rows.txn(account, DAY, "-" + charge, shopping, "FIXTURE ELECTRONICS");

        List<Alert> found = large();

        if (!fires) {
            assertThat(found).isEmpty();
            return;
        }
        assertThat(found).singleElement().satisfies(alert -> {
            assertThat(alert.month()).isEqualTo(APR);
            assertThat(alert.basis()).isEqualTo(new AlertBasis(money(charge), money("200.00"), money("600.00"),
                    LocalDate.of(2025, 10, 17), LocalDate.of(2026, 4, 14), List.of(
                            new AlertSetting("min-amount", money("500.00")),
                            new AlertSetting("times-median", new Quantity(QuantityUnit.RATIO, "3.00")),
                            new AlertSetting("history-days", new Quantity(QuantityUnit.DAYS, "180")),
                            new AlertSetting("min-history", new Quantity(QuantityUnit.COUNT, "5")))));
            AlertDetails.LargeTransaction details = (AlertDetails.LargeTransaction) alert.details();
            assertThat(details.txn().id()).isEqualTo(txn);
            assertThat(details.category().name()).isEqualTo("Shopping");
            assertThat(details.median()).isEqualTo(new BigDecimal("200.00"));
            assertThat(details.historyCount()).isEqualTo(5);
            assertThat(details.timesMedian()).as("charge / 200.00, two decimals").isEqualTo(new BigDecimal("3.00"));
            assertThat(alert.txns()).extracting(TxnRef::id).containsExactly(txn);
        });
    }

    /** History 50, 80, 100, 120, 150: median 100.00, threshold max(500.00, 300.00) = 500.00. */
    @ParameterizedTest
    @CsvSource({"499.99, false", "500.00, true", "500.01, true"})
    void theMinAmountBranch(String charge, boolean fires) {
        history(shopping, "50.00", "80.00", "100.00", "120.00", "150.00");
        rows.txn(account, DAY, "-" + charge, shopping, "FIXTURE ELECTRONICS");

        List<Alert> found = large();

        assertThat(found).hasSize(fires ? 1 : 0);
        if (fires) {
            assertThat(found.getFirst().basis().threshold()).isEqualTo(money("500.00"));
            assertThat(found.getFirst().basis().baseline()).isEqualTo(money("100.00"));
        }
    }

    /** min-history 5: four earlier charges are not enough, five are. */
    @ParameterizedTest
    @CsvSource({"4, false", "5, true"})
    void theHistoryNeedsFiveCharges(int earlier, boolean fires) {
        for (int i = 0; i < earlier; i++) {
            rows.txn(account, DAY.minusDays(10 + i), "-100.00", shopping, "SHOP " + i);
        }
        rows.txn(account, DAY, "-5000.00", shopping, "FIXTURE ELECTRONICS");

        assertThat(large()).hasSize(fires ? 1 : 0);
    }

    /**
     * The window's bounds: four charges inside it, and a fifth on {@code DAY − 181} (out), {@code DAY − 180} (in) or
     * on the day itself (out: "not the same day").
     */
    @ParameterizedTest
    @CsvSource({"181, false", "180, true", "0, false"})
    void theHistoryWindowsBounds(int daysBefore, boolean fires) {
        history(shopping, "100.00", "100.00", "100.00", "100.00");
        rows.txn(account, DAY.minusDays(daysBefore), "-100.00", shopping, "SHOP EDGE");
        rows.txn(account, DAY, "-5000.00", shopping, "FIXTURE ELECTRONICS");

        List<Alert> found = large().stream()
                .filter(a -> ((AlertDetails.LargeTransaction) a.details()).txn().amount()
                        .compareTo(new BigDecimal("-5000.00")) == 0)
                .toList();
        assertThat(found).hasSize(fires ? 1 : 0);
        if (fires) {
            assertThat(found.getFirst().basis().periodStart()).isEqualTo(DAY.minusDays(180));
            assertThat(found.getFirst().basis().periodEnd()).isEqualTo(DAY.minusDays(1));
        }
    }

    /** The history is the same category's charges: Dining charges and money in don't count. */
    @Test
    void theHistoryIsTheSameCategorysCharges() {
        long dining = rows.categoryId("Dining");
        history(dining, "10.00", "10.00", "10.00", "10.00", "10.00");
        history(shopping, "100.00", "100.00", "100.00", "100.00");
        rows.txn(account, DAY.minusDays(5), "40.00", shopping, "SHOP REFUND");
        rows.txn(account, DAY, "-5000.00", shopping, "FIXTURE ELECTRONICS");

        assertThat(large()).as("four Shopping charges").isEmpty();
    }

    /** An uncategorized charge is compared with uncategorized charges, and its category is null. */
    @Test
    void anUncategorizedChargeHasAnUncategorizedHistory() {
        history(null, "100.00", "100.00", "100.00", "100.00", "100.00");
        rows.txn(account, DAY, "-900.00", null, "MYSTERY STORE");

        assertThat(large()).singleElement().satisfies(alert -> {
            AlertDetails.LargeTransaction details = (AlertDetails.LargeTransaction) alert.details();
            assertThat(details.category()).isNull();
            assertThat(details.historyCount()).isEqualTo(5);
            assertThat(alert.basis().threshold()).isEqualTo(money("500.00"));
        });
    }

    /** Transfers are neither alerts nor history: a linked charge of the category is ignored both ways. */
    @Test
    void transfersAreIgnored() {
        history(shopping, "100.00", "100.00", "100.00", "100.00");
        rows.linkOneSided(rows.txn(account, DAY.minusDays(3), "-100.00", shopping, "SHOP LINKED"), true);
        rows.linkOneSided(rows.txn(account, DAY, "-5000.00", shopping, "BIG LINKED"), true);
        rows.txn(account, DAY, "-5000.00", shopping, "FIXTURE ELECTRONICS");

        assertThat(large()).as("four counted charges; the linked large one is not a candidate").isEmpty();
    }

    /** With an even history, percentile_disc takes the lower middle charge: 100, 200, 300, 400, 500, 600 → 300.00. */
    @Test
    void anEvenHistoryTakesTheLowerMiddle() {
        history(shopping, "100.00", "200.00", "300.00", "400.00", "500.00", "600.00");
        rows.txn(account, DAY, "-900.00", shopping, "FIXTURE ELECTRONICS");

        assertThat(large()).singleElement().satisfies(alert -> {
            assertThat(alert.basis().baseline()).isEqualTo(money("300.00"));
            assertThat(alert.basis().threshold()).isEqualTo(money("900.00"));
        });
    }

    /** Earlier charges of the category, one a week back from {@code DAY − 7}. */
    private void history(Long categoryId, String... amounts) {
        for (int i = 0; i < amounts.length; i++) {
            rows.txn(account, DAY.minusDays(7L * (i + 1)), "-" + amounts[i], categoryId, "FIXTURE SHOP " + i);
        }
    }

    private List<Alert> large() {
        return alerts.alerts(Optional.of(APR), Optional.of(APR), Optional.of(AlertType.LARGE_TRANSACTION),
                Optional.empty(), false, 100).items();
    }

    private static Quantity money(String value) {
        return new Quantity(QuantityUnit.MONEY, value);
    }
}
