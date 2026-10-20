package com.finalysis.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
 * Recurring price increase at its threshold (docs/plans/phase-5.md §6.3, §6.6): one cent under, at and over
 * {@code previous + 0.50} after two equal charges, with the basis to the cent; what is not a fixed price, what ends a
 * series, and that only the first charge at a new price raises it. Rows written in SQL, rolled back.
 */
@IntegrationTest
@Transactional
class RecurringPriceIncreaseTest {

    private static final YearMonth JAN = YearMonth.of(2026, 1);
    private static final YearMonth JUN = YearMonth.of(2026, 6);
    private static final YearMonth MAR = YearMonth.of(2026, 3);

    private final AlertRows rows;
    private final AlertQueryService alerts;
    private long subscriptions;
    private long account;

    RecurringPriceIncreaseTest(JdbcTemplate jdbc, AlertQueryService alerts) {
        this.rows = new AlertRows(jdbc);
        this.alerts = alerts;
    }

    @BeforeEach
    void data() {
        subscriptions = rows.categoryId("Subscriptions");
        account = rows.account("7301", LocalDate.of(2025, 12, 1), LocalDate.of(2026, 6, 30));
    }

    /** 9.99 on 01-20 and 02-20, then the charge on 03-20: threshold 9.99 + 0.50 = 10.49. */
    @ParameterizedTest
    @CsvSource({"10.48, false, , ", "10.49, true, 0.50, 5.0", "10.50, true, 0.51, 5.1"})
    void theThreshold(String charge, boolean fires, String increase, String percent) {
        charges("STREAMCO", "2026-01-20", "9.99", "2026-02-20", "9.99");
        long txn = rows.txn(account, LocalDate.of(2026, 3, 20), "-" + charge, subscriptions, "STREAMCO");

        List<Alert> found = increases(JAN, JUN);

        if (!fires) {
            assertThat(found).isEmpty();
            return;
        }
        assertThat(found).singleElement().satisfies(alert -> {
            assertThat(alert.month()).isEqualTo(MAR);
            assertThat(alert.key()).startsWith("recurring-price-increase.2026-03.");
            assertThat(alert.basis()).isEqualTo(new AlertBasis(money(charge), money("9.99"), money("10.49"),
                    LocalDate.of(2026, 1, 20), LocalDate.of(2026, 3, 20), List.of(
                            new AlertSetting("stable-count", new Quantity(QuantityUnit.COUNT, "2")),
                            new AlertSetting("min-increase-amount", money("0.50")))));
            assertThat(alert.details()).isEqualTo(new AlertDetails.RecurringPriceIncrease("STREAMCO", account,
                    new BigDecimal("9.99"), new BigDecimal(charge), new BigDecimal(increase),
                    new BigDecimal(percent)));
            assertThat(alert.txns()).extracting(TxnRef::id).containsExactly(txn);
            assertThat(alert.txns().getFirst().amount()).isEqualTo(new BigDecimal("-" + charge));
        });
    }

    /** Two equal charges are needed before the new one: after a single one, or two different ones, it's no increase. */
    @Test
    void aFixedPriceNeedsTwoEqualChargesBefore() {
        charges("ONE BEFORE", "2026-01-20", "9.99", "2026-02-20", "11.49");
        charges("TWO DIFFERENT", "2026-01-20", "9.99", "2026-02-20", "10.29", "2026-03-20", "11.49");
        charges("THREE EQUAL", "2026-01-20", "9.99", "2026-02-20", "9.99", "2026-03-20", "9.99", "2026-04-20",
                "11.49");

        assertThat(increases(JAN, JUN)).extracting(a -> ((AlertDetails.RecurringPriceIncrease) a.details()).merchant())
                .containsExactly("THREE EQUAL");
    }

    /** Variable bills never have two equal charges in a row (the plan's Clearline Wireless). */
    @Test
    void aVariableBillNeverQualifies() {
        charges("CLEARLINE WIRELESS", "2026-01-15", "67.84", "2026-02-15", "71.06", "2026-03-15", "68.95",
                "2026-04-15", "69.89", "2026-05-15", "70.29");

        assertThat(increases(JAN, JUN)).isEmpty();
    }

    /** Only the first charge at the new price raises it; a second increase after two equal charges is a new alert. */
    @Test
    void onlyTheFirstChargeAtANewPrice() {
        charges("SPOTIFY USA", "2026-01-12", "11.99", "2026-02-12", "11.99", "2026-03-12", "12.99", "2026-04-12",
                "12.99", "2026-05-12", "12.99", "2026-06-12", "13.99");

        assertThat(increases(JAN, JUN)).extracting(Alert::month, a -> a.basis().observed().value())
                .containsExactly(tuple(JUN, "13.99"),
                        tuple(MAR, "12.99"));
    }

    /** A decrease is no alert, and a gap over 35 days ends the series, so the charge after it has no stable price. */
    @Test
    void aDecreaseOrABrokenSeriesIsNoIncrease() {
        charges("CHEAPER", "2026-01-20", "9.99", "2026-02-20", "9.99", "2026-03-20", "8.99");
        charges("GAP", "2026-01-20", "9.99", "2026-02-20", "9.99", "2026-03-28", "11.49");

        assertThat(increases(JAN, JUN)).isEmpty();
    }

    /** Transfers are not charges: a linked new charge, or one with a Transfer category, raises nothing. */
    @Test
    void transfersAreIgnored() {
        charges("LINKED", "2026-01-20", "9.99", "2026-02-20", "9.99");
        long linked = rows.txn(account, LocalDate.of(2026, 3, 20), "-11.49", subscriptions, "LINKED");
        rows.linkOneSided(linked, true);
        long transfer = rows.categoryId("Transfer");
        for (String date : List.of("2026-01-20", "2026-02-20")) {
            rows.txn(account, LocalDate.parse(date), "-9.99", subscriptions, "UNLINKED");
        }
        rows.txn(account, LocalDate.of(2026, 3, 20), "-11.49", transfer, "UNLINKED");

        assertThat(increases(JAN, JUN)).isEmpty();
    }

    /**
     * The same merchant on another account is another series; the alert lists only in its month's range, its key reads
     * back the same alert, and the {@code accountId} filter follows the series' account.
     */
    @Test
    void monthAccountAndKey() {
        charges("STREAMCO", "2026-01-20", "9.99", "2026-02-20", "9.99", "2026-03-20", "11.49");
        long other = rows.account("7302", LocalDate.of(2025, 12, 1), LocalDate.of(2026, 6, 30));
        rows.txn(other, LocalDate.of(2026, 2, 20), "-9.99", subscriptions, "STREAMCO");
        rows.txn(other, LocalDate.of(2026, 3, 20), "-9.99", subscriptions, "STREAMCO");

        List<Alert> found = increases(MAR, MAR);

        assertThat(found).singleElement().satisfies(alert -> assertThat(alerts.alert(alert.key())).isEqualTo(alert));
        assertThat(increases(YearMonth.of(2026, 4), JUN)).isEmpty();
        assertThat(increases(JAN, YearMonth.of(2026, 2))).isEmpty();
        assertThat(alerts.alerts(Optional.of(JAN), Optional.of(JUN), Optional.of(AlertType.RECURRING_PRICE_INCREASE),
                Optional.of(other), false, 100).items()).isEmpty();
    }

    /** Pairs of date and amount, money out, all Subscriptions. */
    private void charges(String merchant, String... dateAmount) {
        for (int i = 0; i < dateAmount.length; i += 2) {
            rows.txn(account, LocalDate.parse(dateAmount[i]), "-" + dateAmount[i + 1], subscriptions, merchant);
        }
    }

    private List<Alert> increases(YearMonth from, YearMonth to) {
        return alerts.alerts(Optional.of(from), Optional.of(to), Optional.of(AlertType.RECURRING_PRICE_INCREASE),
                Optional.empty(), false, 100).items();
    }

    private static Quantity money(String value) {
        return new Quantity(QuantityUnit.MONEY, value);
    }
}
