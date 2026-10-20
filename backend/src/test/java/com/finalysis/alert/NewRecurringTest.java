package com.finalysis.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.analytics.AnalyticsProperties;
import com.finalysis.analytics.CoverageService;
import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.RecurringSeriesQuery;
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
 * New recurring charge at its thresholds (docs/plans/phase-5.md §6.3, §6.6): the third charge of a monthly series raises
 * it when the first came at least 45 days after the account's first covered day (2025-12-01 here: one day under, at and
 * over) and every charge is within 20% of the median (one cent under, at and over, both ways). Rows written in SQL,
 * rolled back.
 */
@IntegrationTest
@Transactional
class NewRecurringTest {

    private static final LocalDate FIRST_COVERED = LocalDate.of(2025, 12, 1);
    private static final YearMonth DEC = YearMonth.of(2025, 12);
    private static final YearMonth JUN = YearMonth.of(2026, 6);

    private final AlertRows rows;
    private final AlertQueryService alerts;
    private final RecurringSeriesQuery series;
    private final CoverageService coverage;
    private final AlertProperties properties;
    private long subscriptions;
    private long account;

    NewRecurringTest(JdbcTemplate jdbc, AlertQueryService alerts, RecurringSeriesQuery series,
                     CoverageService coverage, AlertProperties properties) {
        this.rows = new AlertRows(jdbc);
        this.alerts = alerts;
        this.series = series;
        this.coverage = coverage;
        this.properties = properties;
    }

    @BeforeEach
    void data() {
        subscriptions = rows.categoryId("Subscriptions");
        account = rows.account("7401", FIRST_COVERED, LocalDate.of(2026, 6, 30));
    }

    /** The first charge 44, 45 or 46 days after the first covered day; 4.99 each month after. */
    @ParameterizedTest
    @CsvSource({"2026-01-14, false", "2026-01-15, true", "2026-01-16, true"})
    void theMinPriorDays(String firstDate, boolean fires) {
        LocalDate first = LocalDate.parse(firstDate);
        long a = rows.txn(account, first, "-4.99", subscriptions, "CLOUDBOX STORAGE");
        long b = rows.txn(account, first.plusMonths(1), "-4.99", subscriptions, "CLOUDBOX STORAGE");
        long c = rows.txn(account, first.plusMonths(2), "-4.99", subscriptions, "CLOUDBOX STORAGE");

        List<Alert> found = newRecurring(DEC, JUN);

        if (!fires) {
            assertThat(found).isEmpty();
            return;
        }
        YearMonth month = YearMonth.from(first.plusMonths(2));
        assertThat(found).singleElement().satisfies(alert -> {
            assertThat(alert.month()).as("the month of the third charge").isEqualTo(month);
            assertThat(alert.key()).startsWith("new-recurring." + month + ".");
            assertThat(alert.basis()).isEqualTo(new AlertBasis(count("3"), null, count("3"), first,
                    first.plusMonths(2), List.of(
                            new AlertSetting("min-occurrences", count("3")),
                            new AlertSetting("amount-tolerance-percent", new Quantity(QuantityUnit.PERCENT, "20.0")),
                            new AlertSetting("min-prior-days", new Quantity(QuantityUnit.DAYS, "45")),
                            new AlertSetting("active-days", new Quantity(QuantityUnit.DAYS, "45")))));
            assertThat(alert.details()).isEqualTo(new AlertDetails.NewRecurring("CLOUDBOX STORAGE", account,
                    new BigDecimal("4.99"), 3, first, first.plusMonths(2)));
            assertThat(alert.txns()).extracting(TxnRef::id).containsExactly(a, b, c);
        });
    }

    /**
     * Median 10.00, so every charge must be within 2.00 of it: {@code |charge − 10.00| × 100 ≤ 10.00 × 20}. The odd
     * charge is the third, above or below.
     */
    @ParameterizedTest
    @CsvSource({"12.00, true", "12.01, false", "8.00, true", "7.99, false"})
    void theAmountTolerance(String third, boolean fires) {
        rows.txn(account, LocalDate.of(2026, 1, 15), "-10.00", subscriptions, "FIXTURE GYM");
        rows.txn(account, LocalDate.of(2026, 2, 15), "-10.00", subscriptions, "FIXTURE GYM");
        rows.txn(account, LocalDate.of(2026, 3, 15), "-" + third, subscriptions, "FIXTURE GYM");

        List<Alert> found = newRecurring(DEC, JUN);

        assertThat(found).hasSize(fires ? 1 : 0);
        found.forEach(alert -> assertThat(((AlertDetails.NewRecurring) alert.details()).typicalAmount())
                .isEqualTo(new BigDecimal("10.00")));
    }

    /**
     * Two charges are not yet a series. A fourth charge later doesn't move the alert: it stays in the third charge's
     * month, judged as of that month's end (three occurrences), whatever range lists it.
     */
    @Test
    void theAlertStaysInTheMonthItsSeriesFormed() {
        rows.txn(account, LocalDate.of(2026, 1, 20), "-4.99", subscriptions, "CLOUDBOX STORAGE");
        rows.txn(account, LocalDate.of(2026, 2, 20), "-4.99", subscriptions, "CLOUDBOX STORAGE");
        assertThat(newRecurring(DEC, JUN)).isEmpty();

        rows.txn(account, LocalDate.of(2026, 3, 20), "-4.99", subscriptions, "CLOUDBOX STORAGE");
        rows.txn(account, LocalDate.of(2026, 4, 20), "-5.49", subscriptions, "CLOUDBOX STORAGE");
        List<Alert> wide = newRecurring(DEC, JUN);
        List<Alert> narrow = newRecurring(YearMonth.of(2026, 3), YearMonth.of(2026, 3));

        assertThat(wide).singleElement().satisfies(alert -> {
            assertThat(alert.month()).isEqualTo(YearMonth.of(2026, 3));
            assertThat(alert.basis().observed().value()).isEqualTo("3");
            assertThat(alert.basis().periodEnd()).isEqualTo(LocalDate.of(2026, 3, 20));
        });
        assertThat(narrow).isEqualTo(wide);
        assertThat(alerts.alert(wide.getFirst().key())).isEqualTo(wide.getFirst());
        assertThat(newRecurring(YearMonth.of(2026, 4), JUN)).isEmpty();
    }

    /** A series that started with the data (first charge within 45 days of the first covered day) is not new. */
    @Test
    void aSeriesFromTheStartOfTheDataIsNotNew() {
        for (int month = 0; month < 6; month++) {
            rows.txn(account, LocalDate.of(2025, 12, 5).plusMonths(month), "-15.49", subscriptions, "NETFLIX.COM");
        }

        assertThat(newRecurring(DEC, JUN)).isEmpty();
    }

    /** Money in is never a charge, and a series in another account is judged by that account's coverage. */
    @Test
    void moneyInAndOtherAccounts() {
        long income = rows.categoryId("Income");
        for (int month = 0; month < 3; month++) {
            rows.txn(account, LocalDate.of(2026, 1, 25).plusMonths(month), "250.00", income, "FIXTURE RENTER");
        }
        long later = rows.account("7402", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 6, 30));
        for (int month = 0; month < 3; month++) {
            rows.txn(later, LocalDate.of(2026, 2, 10).plusMonths(month), "-9.99", subscriptions, "STREAMCO");
        }

        assertThat(newRecurring(DEC, JUN)).as("STREAMCO started 9 days into its own account's coverage").isEmpty();
    }

    /**
     * {@code active-days}: the last charge must be within it of the account's last covered day as of the month's end.
     * Judged at the month's end, a series that just reached three charges is at most a month old, so this only bites
     * with a short setting; here 5 days, with the check built by hand.
     */
    @Test
    void theActiveDays() {
        rows.txn(account, LocalDate.of(2026, 1, 20), "-4.99", subscriptions, "CLOUDBOX STORAGE");
        rows.txn(account, LocalDate.of(2026, 2, 20), "-4.99", subscriptions, "CLOUDBOX STORAGE");
        rows.txn(account, LocalDate.of(2026, 3, 20), "-4.99", subscriptions, "CLOUDBOX STORAGE");
        MonthRange march = new MonthRange(YearMonth.of(2026, 3), YearMonth.of(2026, 3));

        assertThat(check(45).find(march)).hasSize(1);
        assertThat(check(11).find(march)).as("03-20 to 03-31 is 11 days").hasSize(1);
        assertThat(check(10).find(march)).isEmpty();
    }

    private NewRecurringCheck check(int activeDays) {
        AnalyticsProperties analytics = new AnalyticsProperties(24, 6, null,
                new AnalyticsProperties.Recurring(25, 35, 3, 2, activeDays, 100));
        return new NewRecurringCheck(series, coverage, analytics, properties);
    }

    private List<Alert> newRecurring(YearMonth from, YearMonth to) {
        return alerts.alerts(Optional.of(from), Optional.of(to), Optional.of(AlertType.NEW_RECURRING),
                Optional.empty(), false, 100).items();
    }

    private static Quantity count(String value) {
        return new Quantity(QuantityUnit.COUNT, value);
    }
}
