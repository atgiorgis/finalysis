package com.finalysis.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.analytics.AnalyticsFixture.Loaded;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The alert endpoints (docs/plans/phase-5.md §5.2, §9.5) on the fixture: defaults, JSON units, filters, bounds, every
 * 400 with its field, the 404s, and dismissal (idempotent, hidden unless asked for, only for a current alert). The
 * fixture's step 5 alerts: two spikes and a large charge in April, a duplicate in March, all on the card.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AlertApiTest {

    private final MockMvc mockMvc;
    private final ImportTestSupport support;
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    AlertApiTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.mockMvc = mockMvc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @AfterEach
    void cleanUp() {
        support.deleteCreatedAccounts();
        jdbc.update("DELETE FROM alert_dismissal");
    }

    @Test
    void anEmptyDatabaseHasNoAlerts() throws Exception {
        mockMvc.perform(get("/api/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").doesNotExist())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.counts.CATEGORY_SPIKE").value(0))
                .andExpect(jsonPath("$.counts.NEW_RECURRING").value(0))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.limit").value(100));
    }

    /** The default range is three months ending at the latest month (February to April); money and units as text. */
    @Test
    void theDefaultListAndItsJson() throws Exception {
        load();

        JsonNode body = json(mockMvc.perform(get("/api/alerts")).andExpect(status().isOk()));

        assertThat(body.get("range").get("from").asString()).isEqualTo("2026-02");
        assertThat(body.get("range").get("to").asString()).isEqualTo("2026-04");
        assertThat(body.get("items")).hasSize(4);
        assertThat(body.get("items").valueStream().map(i -> i.get("month").asString() + " " + i.get("type").asString())
                .toList()).as("newest month first, then by type").containsExactly(
                "2026-04 CATEGORY_SPIKE", "2026-04 CATEGORY_SPIKE", "2026-04 LARGE_TRANSACTION",
                "2026-03 POSSIBLE_DUPLICATE");
        assertThat(body.get("counts").get("CATEGORY_SPIKE").asInt()).isEqualTo(2);
        assertThat(body.get("counts").get("LARGE_TRANSACTION").asInt()).isEqualTo(1);
        assertThat(body.get("counts").get("POSSIBLE_DUPLICATE").asInt()).isEqualTo(1);

        JsonNode large = body.get("items").get(2);
        assertThat(large.get("key").asString()).matches("large-transaction\\.2026-04\\.[0-9a-f]{16}");
        assertThat(large.get("basis").get("observed").get("unit").asString()).isEqualTo("MONEY");
        assertThat(large.get("basis").get("observed").get("value").asString()).isEqualTo("700.00");
        assertThat(large.get("basis").get("threshold").get("value").asString()).isEqualTo("500.00");
        assertThat(large.get("basis").get("periodStart").asString()).isEqualTo("2025-10-07");
        assertThat(large.get("details").get("type").asString()).isEqualTo("LARGE_TRANSACTION");
        assertThat(large.get("details").get("median").asString()).isEqualTo("30.00");
        assertThat(large.get("details").get("timesMedian").asString()).isEqualTo("23.33");
        assertThat(large.get("txns").get(0).get("amount").asString()).isEqualTo("-700.00");
        assertThat(large.get("txns").get(0).has("rawDescription")).isFalse();
        assertThat(large.get("dismissed").asBoolean()).isFalse();
        assertThat(large.get("dataFlags").get(0).get("type").asString()).isEqualTo("PARTIAL_COVERAGE");

        JsonNode spike = body.get("items").get(0);
        assertThat(spike.get("details").get("increasePercent").asString()).matches("\\d+\\.\\d");
        assertThat(spike.get("details").get("baseline").get("monthsUsed").asInt()).isEqualTo(3);
        assertThat(spike.get("basis").get("settings").get(0).get("name").asString()).isEqualTo("min-increase-percent");
        assertThat(spike.get("basis").get("settings").get(0).get("value").get("value").asString()).isEqualTo("50.0");
    }

    @Test
    void filtersAndBounds() throws Exception {
        Loaded loaded = load();
        long checking = loaded.accounts().get(AnalyticsFixture.CHECKING);
        long card = loaded.accounts().get(AnalyticsFixture.CARD);

        mockMvc.perform(get("/api/alerts").param("type", "POSSIBLE_DUPLICATE"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.type").value("POSSIBLE_DUPLICATE"))
                .andExpect(jsonPath("$.counts.CATEGORY_SPIKE").value(0));
        mockMvc.perform(get("/api/alerts").param("type", "NEW_RECURRING"))
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/alerts").param("accountId", String.valueOf(card)))
                .andExpect(jsonPath("$.items.length()").value(4));
        mockMvc.perform(get("/api/alerts").param("accountId", String.valueOf(checking)))
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/alerts").param("from", "2026-03").param("to", "2026-03"))
                .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/alerts").param("limit", "1"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.counts.CATEGORY_SPIKE").value(2));
        mockMvc.perform(get("/api/alerts").param("limit", "4")).andExpect(jsonPath("$.truncated").value(false));

        invalid(get("/api/alerts").param("limit", "0"), "limit");
        invalid(get("/api/alerts").param("limit", "101"), "limit");
        invalid(get("/api/alerts").param("type", "EVERYTHING"), "type");
        invalid(get("/api/alerts").param("from", "2026-4"), "from");
        invalid(get("/api/alerts").param("from", "2026-05").param("to", "2026-04"), "to");
        invalid(get("/api/alerts").param("from", "2024-01").param("to", "2026-01"), "to");
        invalid(get("/api/alerts").param("includeDismissed", "maybe"), "includeDismissed");
        mockMvc.perform(get("/api/alerts").param("accountId", "999999999")).andExpect(status().isNotFound());
    }

    @Test
    void oneAlertByKey() throws Exception {
        load();
        String key = key("POSSIBLE_DUPLICATE");

        mockMvc.perform(get("/api/alerts/{key}", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.details.merchant").value("FIXTURE GROCER"))
                .andExpect(jsonPath("$.details.amount").value("80.00"))
                .andExpect(jsonPath("$.txns.length()").value(2));
        invalid(get("/api/alerts/{key}", "not-a-key"), "key");
        invalid(get("/api/alerts/{key}", "unknown-check.2026-03.0123456789abcdef"), "key");
        mockMvc.perform(get("/api/alerts/{key}", "possible-duplicate.2026-03.0123456789abcdef"))
                .andExpect(status().isNotFound());
        // The key's month is the only one computed: the same hash under April is no alert.
        mockMvc.perform(get("/api/alerts/{key}", key.replace("2026-03", "2026-04")))
                .andExpect(status().isNotFound());
    }

    /**
     * PUT is idempotent (the first {@code dismissedAt} stays, the note is replaced), the alert is hidden unless
     * {@code includeDismissed}, and DELETE undoes it, also idempotently.
     */
    @Test
    void dismissAndUndismiss() throws Exception {
        load();
        String key = key("LARGE_TRANSACTION");

        JsonNode first = json(dismiss(key, "{\"note\": \"new laptop\"}").andExpect(status().isOk()));
        assertThat(first.get("dismissed").asBoolean()).isTrue();
        assertThat(first.get("note").asString()).isEqualTo("new laptop");
        JsonNode second = json(dismiss(key, "{\"note\": \"work laptop\"}").andExpect(status().isOk()));
        assertThat(second.get("dismissedAt").asString()).isEqualTo(first.get("dismissedAt").asString());
        assertThat(second.get("note").asString()).isEqualTo("work laptop");
        assertThat(jdbc.queryForObject("SELECT alert_type FROM alert_dismissal WHERE alert_key = ?", String.class,
                key)).isEqualTo("LARGE_TRANSACTION");

        mockMvc.perform(get("/api/alerts"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.counts.LARGE_TRANSACTION").value(0));
        mockMvc.perform(get("/api/alerts").param("includeDismissed", "true"))
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[2].dismissed").value(true))
                .andExpect(jsonPath("$.items[2].note").value("work laptop"));
        mockMvc.perform(get("/api/alerts/{key}", key)).andExpect(jsonPath("$.dismissed").value(true));

        mockMvc.perform(delete("/api/alerts/{key}/dismissal", key)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/alerts/{key}/dismissal", key)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/alerts")).andExpect(jsonPath("$.items.length()").value(4));
        mockMvc.perform(get("/api/alerts/{key}", key))
                .andExpect(jsonPath("$.dismissed").value(false))
                .andExpect(jsonPath("$.dismissedAt").doesNotExist());
    }

    /** No body, or no note, dismisses without a note. */
    @Test
    void theNoteIsOptional() throws Exception {
        load();
        mockMvc.perform(put("/api/alerts/{key}/dismissal", key("POSSIBLE_DUPLICATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed").value(true))
                .andExpect(jsonPath("$.note").doesNotExist());
        dismiss(key("CATEGORY_SPIKE"), "{}").andExpect(status().isOk());
    }

    /** Only a current alert can be dismissed, so no arbitrary key is stored; bad keys and notes are 400s. */
    @Test
    void dismissalErrors() throws Exception {
        load();
        String key = key("POSSIBLE_DUPLICATE");

        dismiss("possible-duplicate.2026-03.0123456789abcdef", "{}").andExpect(status().isNotFound());
        invalid(put("/api/alerts/{key}/dismissal", "not-a-key").contentType(MediaType.APPLICATION_JSON).content("{}"),
                "key");
        invalid(put("/api/alerts/{key}/dismissal", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\": \"" + "x".repeat(201) + "\"}"), "note");
        invalid(put("/api/alerts/{key}/dismissal", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\": \"   \"}"), "note");
        invalid(put("/api/alerts/{key}/dismissal", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"x\"}"), "reason");
        invalid(delete("/api/alerts/{key}/dismissal", "not-a-key"), "key");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_dismissal", Integer.class)).isZero();

        dismiss(key, "{\"note\": \"" + "x".repeat(200) + "\"}").andExpect(status().isOk());
    }

    private Loaded load() throws Exception {
        return new AnalyticsFixture(support, jdbc, jsonMapper).load();
    }

    private String key(String type) throws Exception {
        return json(mockMvc.perform(get("/api/alerts").param("type", type))).get("items").get(0).get("key")
                .asString();
    }

    private ResultActions dismiss(String key, String body) throws Exception {
        return mockMvc.perform(put("/api/alerts/{key}/dismissal", key).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void invalid(RequestBuilder request, String field) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value(field));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
