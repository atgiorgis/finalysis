package com.finalysis.chat.summary;

import static com.finalysis.support.ScriptedChatModel.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.analytics.MonthlyTotalsService;
import com.finalysis.chat.citation.Citation;
import com.finalysis.chat.grounding.GroundingStatus;
import com.finalysis.support.ChatIntegrationTest;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.MutableClock;
import com.finalysis.support.ScriptedChatModel;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The monthly summary with AI on (ANA-05, docs/plans/phase-6.md §11.1), on the analytics fixture with a scripted model:
 * one call on the month's records, the same grounding as a chat answer with one retry, the template as the fallback,
 * the turn's limits, the daily cap, the ledger and the AI-06 line. January 2026: income 3,001.10, spending −1,849.99,
 * net 1,151.11, savings rate 38.4% (sample-data/analytics/expected-month-totals.csv).
 */
@ChatIntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class MonthlySummaryTest {

    static final String GROUNDED = "In January 2026 you spent $1,849.99 and received $3,001.10, a net of $1,151.11 and "
            + "a savings rate of 38.4% [c1].";
    static final String TEMPLATE_START = "In January 2026 you spent 1,849.99 and received 3,001.10, a net of 1,151.11 "
            + "and a savings rate of 38.4% [1].";

    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final MonthlySummaryService summaries;
    private final MonthlyTotalsService months;
    private final ScriptedChatModel model;
    private final MutableClock clock;

    MonthlySummaryTest(MockMvc mvc, AccountRepository accountRepository, JdbcTemplate jdbc, JsonMapper json,
                       MonthlySummaryService summaries, MonthlyTotalsService months, ScriptedChatModel model,
                       MutableClock clock) {
        this.support = new ImportTestSupport(mvc, accountRepository, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, json);
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.summaries = summaries;
        this.months = months;
        this.model = model;
        this.clock = clock;
    }

    @BeforeAll
    void importFixture() throws Exception {
        fixture.load();
    }

    @AfterAll
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @BeforeEach
    void reset() {
        model.reset();
        clock.reset();
        jdbc.update("DELETE FROM chat_usage");
    }

    @AfterEach
    void allStepsUsed() {
        jdbc.update("DELETE FROM chat_usage");
        assertThat(model.remaining()).as("every scripted step was used").isZero();
    }

    private MonthlySummaryResponse january() {
        return summaries.summarize(Optional.of("2026-01"));
    }

    private int ledgerRows() {
        return jdbc.queryForObject("SELECT count(*) FROM chat_usage WHERE kind = 'MONTHLY_SUMMARY'", Integer.class);
    }

    @Test
    void aGroundedModelSummaryIsShownWithItsCitations(CapturedOutput output) {
        model.then(text(GROUNDED));

        MonthlySummaryResponse response = january();

        assertThat(response.month()).isEqualTo("2026-01");
        assertThat(response.source()).isEqualTo(MonthlySummaryResponse.Source.MODEL);
        assertThat(response.text()).isEqualTo(GROUNDED.replace("[c1]", "[1]"));
        assertThat(response.grounding().status()).isEqualTo(GroundingStatus.GROUNDED);
        assertThat(response.grounding().figuresChecked()).isEqualTo(4);
        assertThat(response.grounding().retried()).isFalse();
        assertThat(response.citations()).first().isInstanceOfSatisfying(Citation.Total.class,
                total -> assertThat(total.marker()).isEqualTo(1));
        assertThat(response.usage().rounds()).isEqualTo(1);
        assertThat(response.usage().estimatedCost()).isEqualTo("0.001600");
        assertThat(ledgerRows()).isEqualTo(1);

        List<Message> sent = model.prompts().getFirst().getInstructions();
        assertThat(sent).extracting(Message::getMessageType).containsExactly(MessageType.SYSTEM, MessageType.USER);
        String records = sent.get(1).getText();
        assertThat(records).startsWith("<records>{\"result\":{\"month\":\"2026-01\"").endsWith("</records>")
                .contains("\"totals\"").contains("\"trends\"").contains("\"alerts\"").contains("\"recurring\"")
                .doesNotContain("accountId").doesNotContain("5201");
        assertThat(output).contains("ai call role=summary model=test-main-model items=4 inputTokens=1200 "
                + "outputTokens=80").contains("chat monthly summary month=2026-01 source=MODEL grounding=GROUNDED");
    }

    @Test
    void anUngroundedSummaryIsRetriedOnce() {
        model.then(text("In January 2026 you spent $9,999.99 [c1]."), text(GROUNDED));

        MonthlySummaryResponse response = january();

        assertThat(response.source()).isEqualTo(MonthlySummaryResponse.Source.MODEL);
        assertThat(response.grounding().retried()).isTrue();
        assertThat(response.usage().rounds()).isEqualTo(2);
        List<Message> retry = model.prompts().get(1).getInstructions();
        assertThat(retry).extracting(Message::getMessageType).containsExactly(MessageType.SYSTEM, MessageType.USER,
                MessageType.ASSISTANT, MessageType.USER);
        assertThat(retry.getLast().getText()).contains("$9,999.99").contains("not in the records");
    }

    @Test
    void stillUngroundedGivesTheTemplateAndTheModelsTextIsNeverShown() {
        model.then(text("You spent $9,999.99."), text("You spent about $8,888.88."));

        MonthlySummaryResponse response = january();

        assertThat(response.source()).isEqualTo(MonthlySummaryResponse.Source.TEMPLATE);
        assertThat(response.text()).startsWith(TEMPLATE_START).doesNotContain("9,999.99").doesNotContain("8,888.88");
        assertThat(response.grounding().status()).isEqualTo(GroundingStatus.GROUNDED);
        assertThat(response.grounding().retried()).isTrue();
        assertThat(response.usage().rounds()).isEqualTo(2);
        assertThat(ledgerRows()).isEqualTo(1);
    }

    @Test
    void aCutSummaryIsRetriedShorter() {
        model.then(ScriptedChatModel.cut("In January 2026 you spent $1,849.99 and"), text(GROUNDED));

        MonthlySummaryResponse response = january();

        assertThat(response.source()).isEqualTo(MonthlySummaryResponse.Source.MODEL);
        assertThat(model.prompts().get(1).getInstructions().getLast().getText())
                .isEqualTo(MonthlySummaryService.CUT_RETRY);
    }

    @Test
    void theTurnsTimeLimitStopsTheRetryAndGivesTheTemplate(CapturedOutput output) {
        model.then(text("You spent $9,999.99.").after(prompt -> clock.advance(Duration.ofSeconds(50))));

        MonthlySummaryResponse response = january();

        assertThat(response.source()).isEqualTo(MonthlySummaryResponse.Source.TEMPLATE);
        assertThat(response.grounding().retried()).isTrue();
        assertThat(model.prompts()).hasSize(1);
        assertThat(output).contains("source=TEMPLATE").contains("limit=LIMIT");
    }

    @Test
    void aFailedCallIs502AndQuotesNothing() throws Exception {
        model.then(ScriptedChatModel.failing(new IllegalStateException("synthetic 1,849.99")));

        String body = mvc.perform(post("/api/chat/summaries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"month\": \"2026-01\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:finalysis:problem:ai-unavailable"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("synthetic").doesNotContain("1,849.99");
        assertThat(ledgerRows()).isZero();
    }

    @Test
    void theDailyCapIs429BeforeAnyCall() throws Exception {
        jdbc.update("INSERT INTO chat_usage (kind, model, calls, estimated_cost, created_at) "
                + "VALUES ('TURN', 'm', 1, 1.000000, ?)", java.sql.Timestamp.from(clock.instant()));

        mvc.perform(post("/api/chat/summaries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"month\": \"2026-01\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("urn:finalysis:problem:chat-budget"));
        assertThat(model.prompts()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"month\": \"2026-13\"}", "{\"month\": \"January\"}", "{\"month\": \"2026-1\"}",
            "{\"month\": \"2026-01\", \"account\": 1}"})
    void aBadRequestIs400(String body) throws Exception {
        mvc.perform(post("/api/chat/summaries").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    void withoutAMonthItIsTheLatestCompleteOne() throws Exception {
        String latest = months.months(Optional.empty(), Optional.empty(), Optional.empty()).latestCompleteMonth()
                .toString();
        model.then(text("A quiet month."));

        mvc.perform(post("/api/chat/summaries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(latest))
                .andExpect(jsonPath("$.source").value("MODEL"))
                .andExpect(jsonPath("$.grounding.status").value("NO_FIGURES"));
    }

    @Test
    void aMonthWithoutDataIsTheTemplateWithoutACall() {
        MonthlySummaryResponse response = summaries.summarize(Optional.of("2024-05"));

        assertThat(response.source()).isEqualTo(MonthlySummaryResponse.Source.TEMPLATE);
        assertThat(response.text()).isEqualTo("There is no statement data for May 2024.");
        assertThat(response.usage()).isNull();
    }
}
