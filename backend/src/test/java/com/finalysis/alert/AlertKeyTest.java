package com.finalysis.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.analytics.AnalyticsFixture.Loaded;
import com.finalysis.analytics.AnalyticsFixture.Statement;
import com.finalysis.api.NotFoundException;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Alert keys are stable (docs/plans/phase-5.md §6.2): deleting a statement and importing the same file again gives
 * its alerts the same keys, because they hash fingerprints (deterministic, ING-03) or a category id, never a txn id;
 * a dismissal survives it. New facts make a new key: a third identical grocer charge joins the group, and the group's
 * alert is new information, not dismissed.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AlertKeyTest {

    private static final YearMonth DEC = YearMonth.of(2025, 12);
    private static final YearMonth APR = YearMonth.of(2026, 4);

    private final MockMvc mockMvc;
    private final ImportTestSupport support;
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final AlertQueryService alerts;
    private final AlertDismissalService dismissals;

    AlertKeyTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper,
                 AlertQueryService alerts, AlertDismissalService dismissals) {
        this.mockMvc = mockMvc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.alerts = alerts;
        this.dismissals = dismissals;
    }

    @AfterEach
    void cleanUp() {
        support.deleteCreatedAccounts();
        jdbc.update("DELETE FROM alert_dismissal");
    }

    /**
     * The card's March statement holds the duplicate pair, April's the large charge, most of April's spikes and the
     * Streamco price increase; both hold Cloudbox charges of the new recurring series. Each is deleted and imported
     * again (categories re-applied from the key, as the user's would be kept by their rules): every key is the same as
     * before, and the dismissed duplicate, price increase and new recurring charge are still dismissed, with their
     * notes.
     */
    @Test
    void keysAndDismissalsSurviveDeletingAndReimportingAStatement() throws Exception {
        AnalyticsFixture fixture = new AnalyticsFixture(support, jdbc, jsonMapper);
        Loaded loaded = fixture.load();
        Map<AlertType, List<String>> before = keys();
        assertThat(before.get(AlertType.POSSIBLE_DUPLICATE)).hasSize(1);
        assertThat(before.get(AlertType.LARGE_TRANSACTION)).hasSize(1);
        assertThat(before.get(AlertType.CATEGORY_SPIKE)).hasSize(2);
        assertThat(before.get(AlertType.RECURRING_PRICE_INCREASE)).hasSize(1);
        assertThat(before.get(AlertType.NEW_RECURRING)).hasSize(1);
        String duplicate = before.get(AlertType.POSSIBLE_DUPLICATE).getFirst();
        Alert dismissed = dismissals.dismiss(duplicate, "same receipt, two cards");
        assertThat(dismissed.dismissed()).isTrue();
        // The new price (03-20) and the series' last two charges (03-09, 04-09) are in the two re-imported files.
        String priceIncrease = before.get(AlertType.RECURRING_PRICE_INCREASE).getFirst();
        String newRecurring = before.get(AlertType.NEW_RECURRING).getFirst();
        Alert priceDismissed = dismissals.dismiss(priceIncrease, "told about it by email");
        dismissals.dismiss(newRecurring, null);

        for (String file : List.of("fixture-card_2026-03.csv", "fixture-card_2026-04.csv")) {
            mockMvc.perform(delete("/api/statements/{id}", loaded.statements().get(file))).andExpect(status().isOk());
            Statement statement = AnalyticsFixture.manifest().stream().filter(s -> s.file().equals(file)).findFirst()
                    .orElseThrow();
            long reimported = fixture.importStatement(loaded.accounts(), statement);
            fixture.applyCategories(file, reimported);
        }
        Map<AlertType, List<String>> after = keys();

        assertThat(after).isEqualTo(before);
        Alert again = alerts.alert(duplicate);
        assertThat(again.dismissed()).isTrue();
        assertThat(again.note()).isEqualTo("same receipt, two cards");
        assertThat(again.dismissedAt()).isEqualTo(dismissed.dismissedAt());
        Alert priceAgain = alerts.alert(priceIncrease);
        assertThat(priceAgain.dismissed()).isTrue();
        assertThat(priceAgain.note()).isEqualTo("told about it by email");
        assertThat(priceAgain.dismissedAt()).isEqualTo(priceDismissed.dismissedAt());
        assertThat(alerts.alert(newRecurring).dismissed()).isTrue();
        assertThat(alerts.alerts(Optional.of(DEC), Optional.of(APR), Optional.empty(), Optional.empty(), false, 100)
                .items()).extracting(Alert::key).doesNotContain(duplicate, priceIncrease, newRecurring);
    }

    /** A statement deleted for good takes its alerts with it; the dismissal stays, harmless, and isn't listed. */
    @Test
    void anAlertWhoseFactsAreGoneIsNotFound() throws Exception {
        Loaded loaded = new AnalyticsFixture(support, jdbc, jsonMapper).load();
        String duplicate = keys().get(AlertType.POSSIBLE_DUPLICATE).getFirst();
        dismissals.dismiss(duplicate, null);

        mockMvc.perform(delete("/api/statements/{id}", loaded.statements().get("fixture-card_2026-03.csv")))
                .andExpect(status().isOk());

        assertThatThrownBy(() -> alerts.alert(duplicate)).isInstanceOf(NotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_dismissal", Integer.class)).isEqualTo(1);
    }

    /** A third identical charge joins the pair: one group of three, a new key, not dismissed; the old key is gone. */
    @Test
    void aFurtherDuplicateMakesANewKey() throws Exception {
        Loaded loaded = new AnalyticsFixture(support, jdbc, jsonMapper).load();
        String pair = keys().get(AlertType.POSSIBLE_DUPLICATE).getFirst();
        dismissals.dismiss(pair, null);
        long original = loaded.txns().get("fixture-card_2026-03.csv:4");
        jdbc.update("""
                INSERT INTO txn (account_id, statement_id, txn_date, raw_description, merchant, amount, source_section,
                                 category_id, category_source, fingerprint)
                SELECT account_id, statement_id, txn_date, raw_description, merchant, amount, source_section,
                       category_id, category_source, 'alert-key-test-third'
                FROM txn WHERE id = ?
                """, original);

        List<Alert> found = alerts.alerts(Optional.of(DEC), Optional.of(APR), Optional.of(AlertType.POSSIBLE_DUPLICATE),
                Optional.empty(), true, 100).items();

        assertThat(found).singleElement().satisfies(alert -> {
            assertThat(alert.key()).isNotEqualTo(pair).startsWith("possible-duplicate.2026-03.");
            assertThat(alert.txns()).hasSize(3);
            assertThat(alert.dismissed()).isFalse();
        });
        assertThatThrownBy(() -> alerts.alert(pair)).isInstanceOf(NotFoundException.class);
    }

    /** Recomputing gives the same keys, and the same alert read alone equals its list entry. */
    @Test
    void keysAreRecomputedIdentically() throws Exception {
        new AnalyticsFixture(support, jdbc, jsonMapper).load();
        List<Alert> first = list();
        List<Alert> second = list();

        assertThat(second).isEqualTo(first);
        for (Alert alert : first) {
            assertThat(alerts.alert(alert.key())).isEqualTo(alert);
        }
    }

    private List<Alert> list() {
        return alerts.alerts(Optional.of(DEC), Optional.of(APR), Optional.empty(), Optional.empty(), true, 100)
                .items();
    }

    private Map<AlertType, List<String>> keys() {
        return list().stream().collect(Collectors.groupingBy(Alert::type,
                Collectors.mapping(Alert::key, Collectors.toList())));
    }
}
