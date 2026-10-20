package com.finalysis.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.analytics.AnalyticsFixture.Loaded;
import com.finalysis.analytics.MonthFlag;
import com.finalysis.analytics.MonthFlagType;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every check on the Phase 5 fixture, imported for real: exactly the rows of the hand-written {@code expected-alerts.csv}
 * (docs/plans/phase-5.md §9.1), every basis column compared with the file as text, and the txns it names. Step 5 wrote
 * the spike, large and duplicate rows; step 6 the Streamco price increase and the Cloudbox new recurring charge.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AlertFixtureTest {

    private final ImportTestSupport support;
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final AlertQueryService alerts;

    AlertFixtureTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper,
                     AlertQueryService alerts) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.alerts = alerts;
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @Test
    void exactlyTheExpectedAlerts() throws Exception {
        Loaded loaded = new AnalyticsFixture(support, jdbc, jsonMapper).load();

        List<Alert> found = alerts.alerts(Optional.of(YearMonth.of(2025, 12)), Optional.of(YearMonth.of(2026, 4)),
                Optional.empty(), Optional.empty(), false, 100).items();

        List<String> expected = new ArrayList<>();
        for (String[] c : AnalyticsFixture.rows("expected-alerts.csv")) {
            String txns = c[9].isEmpty() ? "" : String.join(";", Arrays.stream(c[9].split(";"))
                    .map(ref -> loaded.txns().get(ref).toString()).toList());
            expected.add(String.join(",", c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], txns));
        }
        List<String> actual = found.stream().map(AlertFixtureTest::line).toList();
        assertThat(expected).hasSize(6);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    /**
     * Each alert carries the flags of the months its figures used (§6.1): the March checking statement failed its
     * balance check, the card's April and December are partial. The spike used January to April, the duplicate
     * March, the large charge October to April (its 180-day history), the price increase its stable charges and the
     * new one (January to March), the new recurring charge its series (February to April).
     */
    @Test
    void eachAlertCarriesTheFlagsOfItsMonths() throws Exception {
        Loaded loaded = new AnalyticsFixture(support, jdbc, jsonMapper).load();
        long checking = loaded.accounts().get(AnalyticsFixture.CHECKING);
        long card = loaded.accounts().get(AnalyticsFixture.CARD);
        long march = loaded.statements().get("fixture-checking_2026-03.csv");
        MonthFlag failed = new MonthFlag(MonthFlagType.RECONCILIATION_FAILED, checking, march, null);
        MonthFlag partial = new MonthFlag(MonthFlagType.PARTIAL_COVERAGE, card, null, null);

        List<Alert> found = alerts.alerts(Optional.of(YearMonth.of(2026, 3)), Optional.of(YearMonth.of(2026, 4)),
                Optional.empty(), Optional.empty(), false, 100).items();

        assertThat(found).filteredOn(a -> a.type() == AlertType.CATEGORY_SPIKE).isNotEmpty()
                .allSatisfy(a -> assertThat(a.dataFlags()).containsExactly(failed, partial));
        assertThat(found).filteredOn(a -> a.type() == AlertType.POSSIBLE_DUPLICATE).singleElement()
                .satisfies(a -> assertThat(a.dataFlags()).containsExactly(failed));
        assertThat(found).filteredOn(a -> a.type() == AlertType.LARGE_TRANSACTION).singleElement()
                .satisfies(a -> assertThat(a.dataFlags()).as("December's partial card, once").containsExactly(partial,
                        failed));
        assertThat(found).filteredOn(a -> a.type() == AlertType.RECURRING_PRICE_INCREASE).singleElement()
                .satisfies(a -> assertThat(a.dataFlags()).as("January to March").containsExactly(failed));
        assertThat(found).filteredOn(a -> a.type() == AlertType.NEW_RECURRING).singleElement()
                .satisfies(a -> assertThat(a.dataFlags()).as("February to April").containsExactly(failed, partial));
    }

    /** The file's columns: type, month, subject, unit, observed, baseline, threshold, period, txn ids. */
    private static String line(Alert alert) {
        AlertBasis basis = alert.basis();
        String subject = switch (alert.details()) {
            case AlertDetails.CategorySpike spike -> spike.category().name();
            case AlertDetails.LargeTransaction large -> large.category().name();
            case AlertDetails.PossibleDuplicate duplicate -> duplicate.merchant();
            case AlertDetails.RecurringPriceIncrease increase -> increase.merchant();
            case AlertDetails.NewRecurring recurring -> recurring.merchant();
        };
        String txns = alert.type() == AlertType.CATEGORY_SPIKE ? ""
                : String.join(";", alert.txns().stream().map(t -> Long.toString(t.id())).toList());
        return String.join(",", alert.type().name(), alert.month().toString(), subject,
                basis.observed().unit().name(), basis.observed().value(),
                basis.baseline() == null ? "" : basis.baseline().value(), basis.threshold().value(),
                date(basis.periodStart()), date(basis.periodEnd()), txns);
    }

    private static String date(LocalDate date) {
        return date.toString();
    }
}
