package com.finalysis.chat.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The monthly summary with AI off, the default (ANA-05, docs/plans/phase-6.md §11.1, §18 decision 10): 200 with the
 * template, no model and nothing counted. For every fixture month its income, spending, net and savings rate equal the
 * months endpoint's, in cents, and the text passes the same grounding check a model's would, with its citations.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonthlySummaryTemplateTest {

    private static final Pattern TOTALS = Pattern.compile("you spent ([\\d,]+\\.\\d{2}) and received "
            + "(-?[\\d,]+\\.\\d{2}), a net of (-?[\\d,]+\\.\\d{2})(?: and a savings rate of (-?[\\d.]+)%)?");

    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    MonthlySummaryTemplateTest(MockMvc mvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper json) {
        this.support = new ImportTestSupport(mvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, json);
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.json = json;
    }

    @BeforeAll
    void importFixture() throws Exception {
        fixture.load();
    }

    @AfterAll
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    private JsonNode body(String path, org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private static long cents(String money) {
        return new BigDecimal(money.replace(",", "")).movePointRight(2).longValueExact();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-01", "2026-02", "2026-03", "2026-04"})
    void theTemplatesFiguresEqualTheMonthsEndpoint(String month) throws Exception {
        JsonNode summary = body("summary", post("/api/chat/summaries").contentType(MediaType.APPLICATION_JSON)
                .content("{\"month\": \"" + month + "\"}"));
        JsonNode totals = body("months", get("/api/analytics/months").param("from", month).param("to", month))
                .get("months").get(0);

        assertThat(summary.get("month").stringValue()).isEqualTo(month);
        assertThat(summary.get("source").stringValue()).isEqualTo("TEMPLATE");
        assertThat(summary.get("usage").isNull()).isTrue();
        assertThat(summary.get("grounding").get("status").stringValue()).isEqualTo("GROUNDED");
        assertThat(summary.get("grounding").get("ungrounded").intValue()).isZero();
        assertThat(summary.get("citations").size()).isPositive();

        Matcher m = TOTALS.matcher(summary.get("text").stringValue());
        assertThat(m.find()).as(summary.get("text").stringValue()).isTrue();
        assertThat(cents(m.group(1))).isEqualTo(-cents(totals.get("spending").stringValue()));
        assertThat(cents(m.group(2))).isEqualTo(cents(totals.get("income").stringValue()));
        assertThat(cents(m.group(3))).isEqualTo(cents(totals.get("net").stringValue()));
        if (m.group(4) != null) {
            assertThat(new BigDecimal(m.group(4)))
                    .isEqualByComparingTo(totals.get("savingsRatePercent").asString());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_usage", Integer.class)).isZero();
    }
}
