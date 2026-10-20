package com.finalysis.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.alert.AlertDetails.DuplicateReason;
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
 * Possible duplicate at its thresholds (docs/plans/phase-5.md §6.3, §6.6): the date window (default one day) under,
 * at and over, the amount one cent apart, and what makes two txns "the same charge". Rows written in SQL, rolled back.
 */
@IntegrationTest
@Transactional
class PossibleDuplicateTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);
    private static final YearMonth MAR = YearMonth.of(2026, 3);

    private final AlertRows rows;
    private final AlertQueryService alerts;
    private long groceries;
    private long account;

    PossibleDuplicateTest(JdbcTemplate jdbc, AlertQueryService alerts) {
        this.rows = new AlertRows(jdbc);
        this.alerts = alerts;
    }

    @BeforeEach
    void data() {
        groceries = rows.categoryId("Groceries");
        account = rows.account("7301", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 30));
    }

    /** window-days 1: the same day and one day apart are duplicates; two days apart are not. */
    @ParameterizedTest
    @CsvSource({"0, true", "1, true", "2, false"})
    void theDateWindow(int daysApart, boolean fires) {
        long first = rows.txn(account, DAY, "-84.12", groceries, "FIXTURE GROCER");
        long second = rows.txn(account, DAY.plusDays(daysApart), "-84.12", groceries, "FIXTURE GROCER");

        List<Alert> found = duplicates(MAR);

        if (!fires) {
            assertThat(found).isEmpty();
            return;
        }
        assertThat(found).singleElement().satisfies(alert -> {
            assertThat(alert.month()).isEqualTo(MAR);
            assertThat(alert.basis()).isEqualTo(new AlertBasis(count(2), null, count(2), DAY,
                    DAY.plusDays(daysApart),
                    List.of(new AlertSetting("window-days", new Quantity(QuantityUnit.DAYS, "1")))));
            AlertDetails.PossibleDuplicate details = (AlertDetails.PossibleDuplicate) alert.details();
            assertThat(details.reasons()).containsExactly(DuplicateReason.SAME_MERCHANT_AMOUNT_DATE);
            assertThat(details.amount()).isEqualTo(new BigDecimal("84.12"));
            assertThat(details.merchant()).isEqualTo("FIXTURE GROCER");
            assertThat(alert.txns()).extracting(TxnRef::id).containsExactly(first, second);
        });
    }

    /** One cent apart is a different charge. */
    @Test
    void theAmountMustBeExactlyEqual() {
        rows.txn(account, DAY, "-84.12", groceries, "FIXTURE GROCER");
        rows.txn(account, DAY, "-84.13", groceries, "FIXTURE GROCER");

        assertThat(duplicates(MAR)).isEmpty();
    }

    /** Another merchant, another account, money in or a transfer is not a duplicate charge. */
    @Test
    void merchantAccountDirectionAndTransfersMatter() {
        long other = rows.account("7302", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 30));
        rows.txn(account, DAY, "-50.00", groceries, "FIXTURE GROCER");
        rows.txn(account, DAY, "-50.00", groceries, "OTHER GROCER");
        rows.txn(other, DAY, "-50.00", groceries, "FIXTURE GROCER");
        rows.txn(account, DAY, "25.00", groceries, "FIXTURE REFUNDS");
        rows.txn(account, DAY, "25.00", groceries, "FIXTURE REFUNDS");
        rows.linkOneSided(rows.txn(account, DAY, "-300.00", null, "CARD PAYMENT"), true);
        rows.linkOneSided(rows.txn(account, DAY, "-300.00", null, "CARD PAYMENT"), true);

        assertThat(duplicates(MAR)).isEmpty();
    }

    /** Without a merchant, the raw descriptions are compared instead; they are never returned. */
    @Test
    void withoutAMerchantTheRawDescriptionDecides() {
        rows.txn(account, DAY, "-12.00", null, null, "POS 4411 UNKNOWN", null);
        rows.txn(account, DAY, "-12.00", null, null, "POS 4411 UNKNOWN", null);
        rows.txn(account, DAY, "-13.00", null, null, "POS 4411 UNKNOWN", null);
        rows.txn(account, DAY, "-13.00", null, null, "POS 9999 OTHER", null);

        assertThat(duplicates(MAR)).singleElement().satisfies(alert -> {
            AlertDetails.PossibleDuplicate details = (AlertDetails.PossibleDuplicate) alert.details();
            assertThat(details.merchant()).isNull();
            assertThat(details.amount()).isEqualTo(new BigDecimal("12.00"));
            assertThat(alert.txns()).allSatisfy(t -> assertThat(t.merchant()).isNull());
        });
    }

    /** A group is one alert: three identical charges, the last two days after the first, chained through the second. */
    @Test
    void pairsJoinIntoOneGroup() {
        rows.txn(account, DAY, "-9.00", groceries, "FIXTURE BAKERY");
        rows.txn(account, DAY.plusDays(1), "-9.00", groceries, "FIXTURE BAKERY");
        rows.txn(account, DAY.plusDays(2), "-9.00", groceries, "FIXTURE BAKERY");

        assertThat(duplicates(MAR)).singleElement().satisfies(alert -> {
            assertThat(alert.basis().observed()).isEqualTo(count(3));
            assertThat(alert.basis().periodStart()).isEqualTo(DAY);
            assertThat(alert.basis().periodEnd()).isEqualTo(DAY.plusDays(2));
            assertThat(alert.txns()).hasSize(3);
        });
    }

    /** The same OFX FITID twice is a duplicate whatever the amounts; the amount is then null. */
    @Test
    void theSameExternalIdIsADuplicate() {
        rows.txn(account, DAY, "-20.00", groceries, "FIXTURE GROCER", "FIXTURE GROCER", "FITID-7");
        rows.txn(account, DAY.plusDays(9), "-21.00", groceries, "FIXTURE GROCER 2", "FIXTURE GROCER 2", "FITID-7");

        assertThat(duplicates(MAR)).singleElement().satisfies(alert -> {
            AlertDetails.PossibleDuplicate details = (AlertDetails.PossibleDuplicate) alert.details();
            assertThat(details.reasons()).containsExactly(DuplicateReason.SAME_EXTERNAL_ID);
            assertThat(details.amount()).isNull();
            assertThat(details.merchant()).isNull();
        });
    }

    /** Both reasons are listed when the group has pairs of each. */
    @Test
    void bothReasonsAreListed() {
        rows.txn(account, DAY, "-20.00", groceries, "FIXTURE GROCER", "FIXTURE GROCER", "FITID-8");
        rows.txn(account, DAY, "-20.00", groceries, "FIXTURE GROCER", "FIXTURE GROCER", "FITID-8");

        assertThat(duplicates(MAR)).singleElement().satisfies(alert ->
                assertThat(((AlertDetails.PossibleDuplicate) alert.details()).reasons()).containsExactly(
                        DuplicateReason.SAME_MERCHANT_AMOUNT_DATE, DuplicateReason.SAME_EXTERNAL_ID));
    }

    /** The group's month is its earliest txn's: 03-31 and 04-01 are a March alert, not an April one. */
    @Test
    void theMonthIsTheEarliestTxns() {
        rows.txn(account, LocalDate.of(2026, 3, 31), "-30.00", groceries, "FIXTURE GROCER");
        rows.txn(account, LocalDate.of(2026, 4, 1), "-30.00", groceries, "FIXTURE GROCER");

        assertThat(duplicates(YearMonth.of(2026, 4))).isEmpty();
        assertThat(duplicates(MAR)).singleElement()
                .satisfies(alert -> assertThat(alert.key()).startsWith("possible-duplicate.2026-03."));
    }

    /** At most ten txns are shown; the basis counts every one. */
    @Test
    void atMostTenTxnsAreShown() {
        for (int i = 0; i < 12; i++) {
            rows.txn(account, DAY, "-5.00", groceries, "FIXTURE KIOSK");
        }

        assertThat(duplicates(MAR)).singleElement().satisfies(alert -> {
            assertThat(alert.basis().observed()).isEqualTo(count(12));
            assertThat(alert.txns()).hasSize(10);
        });
    }

    private List<Alert> duplicates(YearMonth month) {
        return alerts.alerts(Optional.of(month), Optional.of(month), Optional.of(AlertType.POSSIBLE_DUPLICATE),
                Optional.empty(), false, 100).items();
    }

    private static Quantity count(int n) {
        return new Quantity(QuantityUnit.COUNT, Integer.toString(n));
    }
}
