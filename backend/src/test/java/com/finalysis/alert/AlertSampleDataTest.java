package com.finalysis.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.finalysis.account.AccountRepository;
import com.finalysis.alert.AlertDetails.DuplicateReason;
import com.finalysis.analytics.Average;
import com.finalysis.analytics.SampleDataImport;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every check on the sample data, with the answer key's categories (docs/plans/phase-5.md §9.3): exactly the plan's
 * five expected alerts, each basis to the cent, and nothing else from March to October 2026. The figures are the
 * plan's hand-worked examples (§6.3, §6.6), never the code's.
 *
 * <p>The result's counts are written to {@code target/phase-5-alerts/} for {@code docs/eval/phase-5-totals.md}.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AlertSampleDataTest {

    private static final YearMonth MAR = YearMonth.of(2026, 3);
    private static final YearMonth OCT = YearMonth.of(2026, 10);

    private final ImportTestSupport support;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final AlertQueryService alerts;

    AlertSampleDataTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper,
                        AlertQueryService alerts) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.alerts = alerts;
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @Test
    void exactlyThePlansFiveAlerts() throws Exception {
        SampleDataImport sample = new SampleDataImport(support, jsonMapper);
        Map<String, Long> accounts = sample.load();
        sample.applyAnswerKey(jdbc, accounts);
        long apexVisa = accounts.get("apex-visa");

        List<Alert> found = alerts.alerts(Optional.of(MAR), Optional.of(OCT), Optional.empty(), Optional.empty(),
                false, 100).items();

        assertThat(found).extracting(Alert::type, Alert::month, AlertSampleDataTest::subject).containsExactly(
                tuple(AlertType.CATEGORY_SPIKE, YearMonth.of(2026, 8), "Dining"),
                tuple(AlertType.CATEGORY_SPIKE, YearMonth.of(2026, 8), "Shopping"),
                tuple(AlertType.LARGE_TRANSACTION, YearMonth.of(2026, 8), "BEST BUY"),
                tuple(AlertType.RECURRING_PRICE_INCREASE, YearMonth.of(2026, 7), "SPOTIFY"),
                tuple(AlertType.POSSIBLE_DUPLICATE, YearMonth.of(2026, 6), "SAFEWAY"));

        // Shopping 2026-08: (282.51 + 226.66 + 224.10) / 3 = 244.42; max(366.63, 344.42) = 366.63.
        Alert shopping = find(found, AlertType.CATEGORY_SPIKE, "Shopping");
        assertThat(shopping.basis()).isEqualTo(basis(money("1183.67"), money("244.42"), money("366.63"),
                "2026-05-01", "2026-07-31", spikeSettings()));
        AlertDetails.CategorySpike shoppingDetails = (AlertDetails.CategorySpike) shopping.details();
        assertThat(shoppingDetails.spent()).isEqualTo(new BigDecimal("1183.67"));
        assertThat(shoppingDetails.baseline()).isEqualTo(new Average(new BigDecimal("244.42"), 3, 3));
        assertThat(shoppingDetails.increaseAmount()).isEqualTo(new BigDecimal("939.25"));
        assertThat(shoppingDetails.increasePercent()).isEqualTo(new BigDecimal("384.3"));

        // Dining 2026-08: (174.75 + 190.10 + 258.52) / 3 = 207.79; 207.79 x 1.5 = 311.685 -> 311.68 > 307.79.
        Alert dining = find(found, AlertType.CATEGORY_SPIKE, "Dining");
        assertThat(dining.basis()).isEqualTo(basis(money("340.63"), money("207.79"), money("311.68"),
                "2026-05-01", "2026-07-31", spikeSettings()));
        AlertDetails.CategorySpike diningDetails = (AlertDetails.CategorySpike) dining.details();
        assertThat(diningDetails.increaseAmount()).isEqualTo(new BigDecimal("132.84"));
        assertThat(diningDetails.increasePercent()).isEqualTo(new BigDecimal("63.9"));

        // Best Buy 2026-08-22: 15 Shopping charges since 2026-02-23, median 66.43; max(500.00, 199.29) = 500.00.
        Alert bestBuy = find(found, AlertType.LARGE_TRANSACTION, "BEST BUY");
        assertThat(bestBuy.basis()).isEqualTo(basis(money("899.99"), money("66.43"), money("500.00"),
                "2026-02-23", "2026-08-21", List.of(
                        new AlertSetting("min-amount", money("500.00")),
                        new AlertSetting("times-median", new Quantity(QuantityUnit.RATIO, "3.00")),
                        new AlertSetting("history-days", new Quantity(QuantityUnit.DAYS, "180")),
                        new AlertSetting("min-history", new Quantity(QuantityUnit.COUNT, "5")))));
        AlertDetails.LargeTransaction large = (AlertDetails.LargeTransaction) bestBuy.details();
        assertThat(large.historyCount()).isEqualTo(15);
        assertThat(large.median()).isEqualTo(new BigDecimal("66.43"));
        assertThat(large.timesMedian()).isEqualTo(new BigDecimal("13.55"));
        assertThat(large.category().name()).isEqualTo("Shopping");
        assertThat(large.txn().txnDate()).isEqualTo(LocalDate.of(2026, 8, 22));
        assertThat(large.txn().amount()).isEqualTo(new BigDecimal("-899.99"));
        assertThat(large.txn().accountId()).isEqualTo(apexVisa);

        // Safeway 2026-06-13, -84.12 twice on Apex Visa.
        Alert safeway = find(found, AlertType.POSSIBLE_DUPLICATE, "SAFEWAY");
        assertThat(safeway.basis()).isEqualTo(basis(new Quantity(QuantityUnit.COUNT, "2"), null,
                new Quantity(QuantityUnit.COUNT, "2"), "2026-06-13", "2026-06-13",
                List.of(new AlertSetting("window-days", new Quantity(QuantityUnit.DAYS, "1")))));
        AlertDetails.PossibleDuplicate duplicate = (AlertDetails.PossibleDuplicate) safeway.details();
        assertThat(duplicate.reasons()).containsExactly(DuplicateReason.SAME_MERCHANT_AMOUNT_DATE);
        assertThat(duplicate.amount()).isEqualTo(new BigDecimal("84.12"));
        assertThat(safeway.txns()).hasSize(2).allSatisfy(t -> {
            assertThat(t.accountId()).isEqualTo(apexVisa);
            assertThat(t.txnDate()).isEqualTo(LocalDate.of(2026, 6, 13));
            assertThat(t.amount()).isEqualTo(new BigDecimal("-84.12"));
        });

        // Spotify on Apex Visa: 11.99 on 05-12 and 06-12 (the two before it), then 12.99 on 07-12: +1.00, +8.3%.
        Alert spotify = find(found, AlertType.RECURRING_PRICE_INCREASE, "SPOTIFY");
        assertThat(spotify.basis()).isEqualTo(basis(money("12.99"), money("11.99"), money("12.49"),
                "2026-05-12", "2026-07-12", List.of(
                        new AlertSetting("stable-count", new Quantity(QuantityUnit.COUNT, "2")),
                        new AlertSetting("min-increase-amount", money("0.50")))));
        AlertDetails.RecurringPriceIncrease increase = (AlertDetails.RecurringPriceIncrease) spotify.details();
        assertThat(increase.accountId()).isEqualTo(apexVisa);
        assertThat(increase.previousAmount()).isEqualTo(new BigDecimal("11.99"));
        assertThat(increase.newAmount()).isEqualTo(new BigDecimal("12.99"));
        assertThat(increase.increaseAmount()).isEqualTo(new BigDecimal("1.00"));
        assertThat(increase.increasePercent()).isEqualTo(new BigDecimal("8.3"));
        assertThat(spotify.txns()).singleElement().satisfies(t -> {
            assertThat(t.accountId()).isEqualTo(apexVisa);
            assertThat(t.txnDate()).isEqualTo(LocalDate.of(2026, 7, 12));
            assertThat(t.amount()).isEqualTo(new BigDecimal("-12.99"));
        });

        assertThat(found).allSatisfy(a -> assertThat(a.dismissed()).isFalse());
        writeReport(found);
    }

    /** The category for a spike; the merchant's first word otherwise, so the assertion reads like the plan. */
    private static String subject(Alert alert) {
        return switch (alert.details()) {
            case AlertDetails.CategorySpike spike -> spike.category().name();
            case AlertDetails.LargeTransaction large -> large.txn().merchant().split(" ")[0] + " "
                    + large.txn().merchant().split(" ")[1];
            case AlertDetails.PossibleDuplicate duplicate -> duplicate.merchant().split(" ")[0];
            case AlertDetails.RecurringPriceIncrease increase -> increase.merchant().split(" ")[0];
            case AlertDetails.NewRecurring recurring -> recurring.merchant().split(" ")[0];
        };
    }

    private static Alert find(List<Alert> alerts, AlertType type, String subject) {
        return alerts.stream().filter(a -> a.type() == type && subject(a).equals(subject)).findFirst().orElseThrow();
    }

    private static List<AlertSetting> spikeSettings() {
        return List.of(new AlertSetting("min-increase-percent", new Quantity(QuantityUnit.PERCENT, "50.0")),
                new AlertSetting("min-increase-amount", money("100.00")),
                new AlertSetting("min-baseline-months", new Quantity(QuantityUnit.COUNT, "3")));
    }

    private static AlertBasis basis(Quantity observed, Quantity baseline, Quantity threshold, String from, String to,
                                    List<AlertSetting> settings) {
        return new AlertBasis(observed, baseline, threshold, LocalDate.parse(from), LocalDate.parse(to), settings);
    }

    private static Quantity money(String value) {
        return new Quantity(QuantityUnit.MONEY, value);
    }

    private static void writeReport(List<Alert> found) throws IOException {
        Path dir = Path.of("target", "phase-5-alerts");
        Files.createDirectories(dir);
        StringBuilder lines = new StringBuilder("type,month,subject,observed,baseline,threshold\n");
        for (Alert a : found) {
            lines.append(a.type()).append(',').append(a.month()).append(',').append(subject(a)).append(',')
                    .append(a.basis().observed().value()).append(',')
                    .append(a.basis().baseline() == null ? "" : a.basis().baseline().value()).append(',')
                    .append(a.basis().threshold().value()).append('\n');
        }
        Files.writeString(dir.resolve("sample-data-alerts.csv"), lines);
    }
}
