package com.finalysis.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.analytics.Average;
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
 * Category spike at its thresholds (docs/plans/phase-5.md §6.3, §6.6): one cent under, at and over each branch of
 * {@code max(baseline × 1.5, baseline + 100.00)}, with the basis to the cent. One account, its statement January to
 * April 2026, so January to March are complete and are April's baseline. Rows written in SQL, rolled back.
 */
@IntegrationTest
@Transactional
class CategorySpikeTest {

    private static final YearMonth APR = YearMonth.of(2026, 4);
    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate APR_30 = LocalDate.of(2026, 4, 30);

    private final AlertRows rows;
    private final AlertQueryService alerts;
    private long dining;

    CategorySpikeTest(JdbcTemplate jdbc, AlertQueryService alerts) {
        this.rows = new AlertRows(jdbc);
        this.alerts = alerts;
    }

    @BeforeEach
    void categories() {
        dining = rows.categoryId("Dining");
    }

    /**
     * The percent branch, with the rounding §6.6 describes: (174.75 + 190.10 + 258.52) / 3 = 207.79;
     * 207.79 × 1.5 = 311.685 → 311.68 (HALF_EVEN), above 207.79 + 100.00 = 307.79. At 311.68 the unrounded rule
     * (311.68 ≥ 311.685) would say no; the decision is made on the shown threshold, so it fires.
     */
    @ParameterizedTest
    @CsvSource({"311.67, false", "311.68, true", "311.69, true"})
    void thePercentBranch(String april, boolean fires) {
        long account = rows.account("7101", JAN_1, APR_30);
        months(account, dining, "174.75", "190.10", "258.52", april);

        List<Alert> found = spikes(APR);

        if (!fires) {
            assertThat(found).isEmpty();
            return;
        }
        assertThat(found).singleElement().satisfies(alert -> {
            assertThat(alert.month()).isEqualTo(APR);
            assertThat(alert.basis()).isEqualTo(new AlertBasis(money(april), money("207.79"), money("311.68"),
                    JAN_1, LocalDate.of(2026, 3, 31), List.of(
                            new AlertSetting("min-increase-percent", new Quantity(QuantityUnit.PERCENT, "50.0")),
                            new AlertSetting("min-increase-amount", money("100.00")),
                            new AlertSetting("min-baseline-months", new Quantity(QuantityUnit.COUNT, "3")))));
            AlertDetails.CategorySpike details = (AlertDetails.CategorySpike) alert.details();
            assertThat(details.category().name()).isEqualTo("Dining");
            assertThat(details.spent()).isEqualTo(new BigDecimal(april));
            assertThat(details.baseline()).isEqualTo(new Average(new BigDecimal("207.79"), 3, 3));
            assertThat(details.increaseAmount()).isEqualTo(new BigDecimal(april).subtract(new BigDecimal("207.79")));
            assertThat(alert.txns()).singleElement().satisfies(t -> {
                assertThat(t.accountId()).isEqualTo(account);
                assertThat(t.amount()).isEqualTo(new BigDecimal(april).negate());
            });
        });
    }

    /** The amount branch: baseline 150.00; 150.00 × 1.5 = 225.00 is below 150.00 + 100.00 = 250.00. */
    @ParameterizedTest
    @CsvSource({"249.99, false", "250.00, true", "250.01, true"})
    void theAmountBranch(String april, boolean fires) {
        long account = rows.account("7102", JAN_1, APR_30);
        months(account, dining, "150.00", "150.00", "150.00", april);

        List<Alert> found = spikes(APR);

        assertThat(found).hasSize(fires ? 1 : 0);
        if (fires) {
            Alert alert = found.getFirst();
            assertThat(alert.basis().observed()).isEqualTo(money(april));
            assertThat(alert.basis().baseline()).isEqualTo(money("150.00"));
            assertThat(alert.basis().threshold()).isEqualTo(money("250.00"));
            assertThat(((AlertDetails.CategorySpike) alert.details()).increasePercent())
                    .as("(april - 150.00) / 150.00 x 100, one decimal").isEqualTo(new BigDecimal("66.7"));
        }
    }

    /** min-baseline-months 3: with January not covered, April's baseline is over two months, so no alert. */
    @Test
    void aBaselineOfTwoMonthsIsNotEnough() {
        long account = rows.account("7103", LocalDate.of(2026, 2, 1), APR_30);
        rows.txn(account, LocalDate.of(2026, 2, 10), "-100.00", dining, "DINER");
        rows.txn(account, LocalDate.of(2026, 3, 10), "-100.00", dining, "DINER");
        rows.txn(account, LocalDate.of(2026, 4, 10), "-5000.00", dining, "DINER");

        assertThat(spikes(APR)).isEmpty();
    }

    /** A baseline of 0.00 (three complete months without the category) is skipped: any first spending would spike. */
    @Test
    void aZeroBaselineIsSkipped() {
        long account = rows.account("7104", JAN_1, APR_30);
        rows.txn(account, LocalDate.of(2026, 4, 10), "-5000.00", dining, "DINER");

        assertThat(spikes(APR)).isEmpty();
    }

    /** A partial month is checked against complete months: April covered to the 15th still spikes. */
    @Test
    void aPartialMonthIsChecked() {
        long account = rows.account("7105", JAN_1, LocalDate.of(2026, 4, 15));
        months(account, dining, "150.00", "150.00", "150.00", "250.00");

        assertThat(spikes(APR)).singleElement()
                .satisfies(alert -> assertThat(alert.basis().threshold()).isEqualTo(money("250.00")));
    }

    /** A refund in the category nets in its month: 300.00 spent and 50.01 back is 249.99, under 250.00. */
    @Test
    void aRefundInTheMonthNets() {
        long account = rows.account("7106", JAN_1, APR_30);
        months(account, dining, "150.00", "150.00", "150.00", "300.00");
        rows.txn(account, LocalDate.of(2026, 4, 20), "50.01", dining, "DINER");

        assertThat(spikes(APR)).isEmpty();
    }

    /** Uncategorized never spikes: it rises and falls with AI runs (§6.3). */
    @Test
    void uncategorizedNeverSpikes() {
        long account = rows.account("7107", JAN_1, APR_30);
        months(account, null, "150.00", "150.00", "150.00", "5000.00");

        assertThat(spikes(APR)).isEmpty();
    }

    /** The txns shown are the category's largest charges in the month, at most ten; the basis counts them all. */
    @Test
    void theLargestChargesAreShownAtMostTen() {
        long account = rows.account("7108", JAN_1, APR_30);
        months(account, dining, "100.00", "100.00", "100.00", "100.00");
        for (int i = 1; i <= 11; i++) {
            rows.txn(account, LocalDate.of(2026, 4, 11 + i), "-" + (20 + i) + ".00", dining, "DINER " + i);
        }

        Alert alert = spikes(APR).getFirst();

        assertThat(alert.basis().observed()).as("100.00 + 21.00 + ... + 31.00").isEqualTo(money("386.00"));
        assertThat(alert.txns()).hasSize(10);
        assertThat(alert.txns().getFirst().amount()).isEqualTo(new BigDecimal("-100.00"));
        assertThat(alert.txns().getLast().amount()).as("100.00, then 31.00 down to 23.00")
                .isEqualTo(new BigDecimal("-23.00"));
    }

    /** One charge on the 10th of each month, January to April. */
    private void months(long account, Long categoryId, String jan, String feb, String mar, String apr) {
        String[] amounts = {jan, feb, mar, apr};
        for (int m = 0; m < amounts.length; m++) {
            rows.txn(account, LocalDate.of(2026, 1 + m, 10), "-" + amounts[m], categoryId, "FIXTURE DINER");
        }
    }

    private List<Alert> spikes(YearMonth month) {
        return alerts.alerts(Optional.of(month), Optional.of(month), Optional.of(AlertType.CATEGORY_SPIKE),
                Optional.empty(), false, 100).items();
    }

    private static Quantity money(String value) {
        return new Quantity(QuantityUnit.MONEY, value);
    }
}
