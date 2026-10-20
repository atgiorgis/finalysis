package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture.Loaded;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/analytics/recurring} (ANA-07; docs/plans/phase-5.md §6.5, §9.5) on the Phase 5 fixture: the series
 * equal the hand-written {@code expected-recurring.csv}, compared as text; JSON units; filters; the bounds and every
 * 400 with its field; the 404. Read-only and bounded like every analytics endpoint.
 */
@IntegrationTest
@AutoConfigureMockMvc
class RecurringChargesApiTest {

    private final MockMvc mockMvc;
    private final ImportTestSupport support;
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    RecurringChargesApiTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.mockMvc = mockMvc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    /** Every column of every row of the file, as text; the series are listed newest last charge first. */
    @Test
    void theFixtureSeriesAreTheHandWrittenOnes() throws Exception {
        Loaded loaded = load();

        JsonNode body = json(get("/api/analytics/recurring").param("status", "ALL"));

        List<String> expected = new ArrayList<>();
        for (String[] c : AnalyticsFixture.rows("expected-recurring.csv")) {
            expected.add(String.join(",", c));
        }
        List<String> actual = body.get("items").valueStream().map(i -> line(i, loaded.accounts())).toList();
        assertThat(expected).hasSize(3);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(actual).extracting(l -> l.split(",")[1]).as("newest last charge first")
                .containsExactly("CLOUDBOX STORAGE", "FIXTURE PROPERTY RENT", "STREAMCO");
    }

    /** Defaults: ACTIVE, limit 50; money as two-decimal strings, dates ISO, the category as a reference. */
    @Test
    void theDefaultsAndTheJson() throws Exception {
        Loaded loaded = load();

        JsonNode body = json(get("/api/analytics/recurring"));

        assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.get("limit").asInt()).isEqualTo(50);
        assertThat(body.get("accountId").isNull()).isTrue();
        assertThat(body.get("truncated").asBoolean()).isFalse();
        assertThat(body.get("items")).hasSize(3);
        JsonNode streamco = body.get("items").get(2);
        assertThat(streamco.get("accountId").asLong()).isEqualTo(loaded.accounts().get(AnalyticsFixture.CARD));
        assertThat(streamco.get("merchant").asString()).isEqualTo("STREAMCO");
        assertThat(streamco.get("category").get("name").asString()).isEqualTo("Subscriptions");
        assertThat(streamco.get("category").get("kind").asString()).isEqualTo("EXPENSE");
        assertThat(streamco.get("cadence").asString()).isEqualTo("MONTHLY");
        assertThat(streamco.get("typicalAmount").asString()).isEqualTo("9.99");
        assertThat(streamco.get("lastAmount").asString()).isEqualTo("11.49");
        assertThat(streamco.get("fixedPrice").asBoolean()).isFalse();
        assertThat(streamco.get("occurrences").asInt()).isEqualTo(4);
        assertThat(streamco.get("firstDate").asString()).isEqualTo("2025-12-20");
        assertThat(streamco.get("nextExpectedDate").asString()).isEqualTo("2026-04-20");
        assertThat(streamco.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(streamco.has("rawDescription")).isFalse();
    }

    /**
     * Filters by account and status. A later checking statement with no rent in it (May and June, covered by a
     * statement row with no txns) leaves the rent's last charge 88 days before the account's last covered day:
     * {@code ENDED}.
     */
    @Test
    void filters() throws Exception {
        Loaded loaded = load();
        long checking = loaded.accounts().get(AnalyticsFixture.CHECKING);
        long card = loaded.accounts().get(AnalyticsFixture.CARD);

        assertThat(merchants(get("/api/analytics/recurring").param("accountId", String.valueOf(card))))
                .containsExactly("CLOUDBOX STORAGE", "STREAMCO");
        assertThat(merchants(get("/api/analytics/recurring").param("accountId", String.valueOf(checking))))
                .containsExactly("FIXTURE PROPERTY RENT");
        assertThat(merchants(get("/api/analytics/recurring").param("status", "ENDED"))).isEmpty();

        jdbc.update("""
                INSERT INTO statement (account_id, period_start, period_end, source_file_name, file_hash, parser)
                VALUES (?, DATE '2026-05-01', DATE '2026-06-30', 'later.csv', 'recurring-api-later', 'csv')
                """, checking);

        assertThat(merchants(get("/api/analytics/recurring").param("status", "ENDED")))
                .containsExactly("FIXTURE PROPERTY RENT");
        assertThat(merchants(get("/api/analytics/recurring"))).containsExactly("CLOUDBOX STORAGE", "STREAMCO");
        assertThat(merchants(get("/api/analytics/recurring").param("status", "ALL"))).hasSize(3);
    }

    /** {@code limit} 1 to 100, {@code truncated} beyond it; bad values are 400s naming the field; an unknown account 404. */
    @Test
    void boundsAndErrors() throws Exception {
        load();

        mockMvc.perform(get("/api/analytics/recurring").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].merchant").value("CLOUDBOX STORAGE"))
                .andExpect(jsonPath("$.truncated").value(true));
        mockMvc.perform(get("/api/analytics/recurring").param("limit", "3"))
                .andExpect(jsonPath("$.truncated").value(false));
        mockMvc.perform(get("/api/analytics/recurring").param("limit", "100")).andExpect(status().isOk());

        invalid(get("/api/analytics/recurring").param("limit", "0"), "limit");
        invalid(get("/api/analytics/recurring").param("limit", "101"), "limit");
        invalid(get("/api/analytics/recurring").param("limit", "many"), "limit");
        invalid(get("/api/analytics/recurring").param("status", "SOMETIMES"), "status");
        invalid(get("/api/analytics/recurring").param("accountId", "card"), "accountId");
        mockMvc.perform(get("/api/analytics/recurring").param("accountId", "999999999"))
                .andExpect(status().isNotFound());
    }

    private Loaded load() throws Exception {
        return new AnalyticsFixture(support, jdbc, jsonMapper).load();
    }

    /** The file's columns: account slug, merchant, category, cadence, amounts, fixed price, count, dates, status. */
    private static String line(JsonNode item, Map<String, Long> accounts) {
        String account = accounts.entrySet().stream().filter(e -> e.getValue() == item.get("accountId").asLong())
                .findFirst().orElseThrow().getKey();
        return String.join(",", account, item.get("merchant").asString(),
                item.get("category").get("name").asString(), item.get("cadence").asString(),
                item.get("typicalAmount").asString(), item.get("lastAmount").asString(),
                Boolean.toString(item.get("fixedPrice").asBoolean()), Integer.toString(item.get("occurrences").asInt()),
                item.get("firstDate").asString(), item.get("lastDate").asString(),
                item.get("nextExpectedDate").asString(), item.get("status").asString());
    }

    private List<String> merchants(RequestBuilder request) throws Exception {
        return json(request).get("items").valueStream().map(i -> i.get("merchant").asString()).toList();
    }

    private JsonNode json(RequestBuilder request) throws Exception {
        return jsonMapper.readTree(mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private void invalid(RequestBuilder request, String field) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value(field));
    }
}
