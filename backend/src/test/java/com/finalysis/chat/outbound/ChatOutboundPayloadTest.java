package com.finalysis.chat.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.alert.AlertQueryService;
import com.finalysis.analytics.AccountTotalsService;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.analytics.CategoryTotalsService;
import com.finalysis.analytics.ComparisonMeasure;
import com.finalysis.analytics.ComparisonService;
import com.finalysis.analytics.CoverageService;
import com.finalysis.analytics.DrillDownFilter;
import com.finalysis.analytics.DrillDownService;
import com.finalysis.analytics.MerchantTotalsService;
import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.MonthlyTotalsService;
import com.finalysis.analytics.RecurringChargesService;
import com.finalysis.analytics.RecurringStatusFilter;
import com.finalysis.analytics.TrendsService;
import com.finalysis.chat.ChatInjectionFixture;
import com.finalysis.chat.ToolCall;
import com.finalysis.chat.ToolExecutor;
import com.finalysis.chat.ToolOutcome;
import com.finalysis.chat.ToolStatus;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * What leaves the machine, per tool (docs/plans/phase-6.md §12.6, step 2): the real services on the fixtures, projected
 * and guarded, each payload equal to a hand-checked expected document in {@code src/test/resources/chat/outbound/}.
 * <ul>
 *   <li>The injection fixture for every tool that has rows there; the analytics fixture for the alerts (one of each
 *       detail type) and the recurring series, which a one-month fixture can't have. A fresh turn per tool, so refs
 *       start at 1.</li>
 *   <li>The injection fixture's hostile descriptions and its canary 999.99 reach no payload: every hostile merchant goes
 *       out as {@code MERCHANT WITHHELD}, and none of their words, the account's name, institution or last four, or any
 *       id field appears anywhere.</li>
 *   <li>The log counts withheld names and says nothing else about them.</li>
 * </ul>
 * {@code search_transactions} and {@code get_merchant_totals} are built with search (step 6) and get their expected
 * payloads there.
 */
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ChatOutboundPayloadTest {

    static final Path EXPECTED = Path.of("src/test/resources/chat/outbound");
    static final YearMonth MARCH = YearMonth.of(2026, 3);

    /** Words of the injection fixture's hostile descriptions; none may reach a payload or the log. */
    static final List<String> HOSTILE = List.of("999.99", "IGNORE", "INSTRUCTION", "SYSTEM", "PROMPT", "ASSISTANT",
            "REVEAL", "PLEASE", "REPORT", "THANKS", "QUESTION", "ROLE", "DISMISS", "SEND ALL", "ALL MONEY", "99887766",
            "ADMIN", "DELETE", "ANSWER", "ALWAYS", "YOU ARE");

    private final ImportTestSupport support;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final OutboundTurnFactory turns;
    private final OutboundProjector projector;
    private final ChatOutbound outbound;
    private final ContextBlockFactory context;
    private final MonthlyTotalsService months;
    private final CategoryTotalsService categories;
    private final AccountTotalsService accounts;
    private final MerchantTotalsService merchants;
    private final TrendsService trends;
    private final ComparisonService comparison;
    private final DrillDownService drillDown;
    private final RecurringChargesService recurring;
    private final AlertQueryService alerts;
    private final CoverageService coverage;
    private final ToolExecutor executor;

    ChatOutboundPayloadTest(MockMvc mockMvc, AccountRepository accountRepository, JdbcTemplate jdbc, JsonMapper json,
                            OutboundTurnFactory turns, OutboundProjector projector, ChatOutbound outbound,
                            ContextBlockFactory context, MonthlyTotalsService months, CategoryTotalsService categories,
                            AccountTotalsService accounts, MerchantTotalsService merchants, TrendsService trends,
                            ComparisonService comparison, DrillDownService drillDown, RecurringChargesService recurring,
                            AlertQueryService alerts, CoverageService coverage, ToolExecutor executor) {
        this.executor = executor;
        this.support = new ImportTestSupport(mockMvc, accountRepository, jdbc);
        this.jdbc = jdbc;
        this.json = json;
        this.turns = turns;
        this.projector = projector;
        this.outbound = outbound;
        this.context = context;
        this.months = months;
        this.categories = categories;
        this.accounts = accounts;
        this.merchants = merchants;
        this.trends = trends;
        this.comparison = comparison;
        this.drillDown = drillDown;
        this.recurring = recurring;
        this.alerts = alerts;
        this.coverage = coverage;
    }

    @AfterEach
    void cleanUp() {
        support.deleteCreatedAccounts();
    }

    /** Each tool's payload on the injection fixture, keyed by its expected file. */
    private Map<String, String> injectionPayloads() {
        Map<String, Function<OutboundTurn, Record>> tools = new LinkedHashMap<>();
        tools.put("context-block", t -> context.build(t, LocalDate.of(2026, 4, 1)));
        tools.put("get_month_totals", t -> projector.monthTotals(
                months.months(Optional.of(MARCH), Optional.of(MARCH), Optional.empty()), t));
        tools.put("get_category_totals", t -> projector.categoryTotals(categories.categories(Optional.of(MARCH),
                Optional.of(MARCH), Optional.empty(), Optional.empty(), false), t));
        tools.put("get_account_totals", t -> projector.accountTotals(
                accounts.accounts(Optional.of(MARCH), Optional.of(MARCH)), t));
        tools.put("get_top_merchants", t -> projector.topMerchants(merchants.merchants(Optional.of(MARCH),
                Optional.of(MARCH), Optional.empty(), Optional.empty(), 20), t));
        tools.put("get_trends", t -> projector.trends(
                trends.trends(Optional.of(MARCH), Optional.empty(), Optional.empty()), t));
        tools.put("compare_periods", t -> projector.comparison(comparison.compare(ComparisonMeasure.SPENDING,
                Optional.empty(), Optional.empty(), new MonthRange(MARCH, MARCH),
                new MonthRange(MARCH.minusMonths(1), MARCH.minusMonths(1))), t));
        DrillDownFilter march = new DrillDownFilter(Optional.of(MARCH), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, 0, 25);
        tools.put("list_transactions", t -> projector.transactions(drillDown.transactions(march), march, 25, t));
        tools.put("get_recurring_charges", t -> projector.recurring(
                recurring.recurring(Optional.empty(), RecurringStatusFilter.ALL, 25), t));
        tools.put("get_alerts", t -> projector.alerts(alerts.alerts(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false, 20), t));
        tools.put("get_data_coverage", t -> projector.coverage(coverage.coverage(), t));
        return send(tools);
    }

    private Map<String, String> send(Map<String, Function<OutboundTurn, Record>> tools) {
        Map<String, String> payloads = new LinkedHashMap<>();
        tools.forEach((name, tool) -> {
            OutboundTurn turn = turns.start();
            payloads.put(name, json.writeValueAsString(outbound.send(name, tool.apply(turn), turn, 0, 0).view()));
        });
        return payloads;
    }

    @Test
    void everyToolSendsExactlyItsExpectedPayloadOnTheInjectionFixture() throws Exception {
        new ChatInjectionFixture(support, jdbc, json).load();

        Map<String, String> payloads = injectionPayloads();

        assertThat(payloads).hasSize(11);
        payloads.forEach((name, payload) -> assertThat(json.readTree(payload)).as(name).isEqualTo(expected(name)));
    }

    /**
     * Step 3: the same documents through {@link ToolExecutor}, from the model's arguments. What a tool sends is its
     * {@code result}, exactly the projected and checked view above; nothing else of the service record reaches it.
     */
    @Test
    void theExecutorSendsExactlyTheExpectedPayloads() throws Exception {
        new ChatInjectionFixture(support, jdbc, json).load();
        Map<String, String> calls = new LinkedHashMap<>();
        calls.put("get_month_totals", "{\"from\": \"2026-03\", \"to\": \"2026-03\"}");
        calls.put("get_category_totals", "{\"from\": \"2026-03\", \"to\": \"2026-03\"}");
        calls.put("get_account_totals", "{\"from\": \"2026-03\", \"to\": \"2026-03\"}");
        calls.put("get_top_merchants", "{\"from\": \"2026-03\", \"to\": \"2026-03\", \"limit\": 20}");
        calls.put("get_trends", "{\"month\": \"2026-03\"}");
        calls.put("compare_periods", "{\"measure\": \"SPENDING\", \"a\": {\"month\": \"2026-03\"}, "
                + "\"b\": {\"month\": \"2026-02\"}}");
        calls.put("list_transactions", "{\"month\": \"2026-03\", \"limit\": 25}");
        calls.put("get_recurring_charges", "{\"status\": \"ALL\", \"limit\": 25}");
        calls.put("get_alerts", "{\"limit\": 20}");
        calls.put("get_data_coverage", "{}");

        StringBuilder all = new StringBuilder();
        calls.forEach((tool, arguments) -> {
            ToolOutcome outcome = executor.execute(new ToolCall(tool, arguments), turns.start());
            assertThat(outcome.status()).as(tool).isEqualTo(ToolStatus.OK);
            assertThat(json.readTree(outcome.json()).get("result")).as(tool).isEqualTo(expected(tool));
            all.append(outcome.json()).append('\n');
        });

        assertThat(HOSTILE).allSatisfy(word -> assertThat(all.toString().toUpperCase()).doesNotContain(word));
        assertThat(all.toString()).doesNotContain(ChatInjectionFixture.NAME).doesNotContain("Halden")
                .doesNotContain(ChatInjectionFixture.LAST_FOUR).doesNotContain("\"accountId\"")
                .doesNotContain("\"id\"");
    }

    @Test
    void alertsOfEveryTypeAndRecurringSeriesSendTheirExpectedPayloads() throws Exception {
        new AnalyticsFixture(support, jdbc, json).load();
        Map<String, Function<OutboundTurn, Record>> tools = new LinkedHashMap<>();
        tools.put("analytics-get_alerts", t -> projector.alerts(alerts.alerts(Optional.of(MARCH),
                Optional.of(MARCH.plusMonths(1)), Optional.empty(), Optional.empty(), false, 20), t));
        tools.put("analytics-get_recurring_charges", t -> projector.recurring(
                recurring.recurring(Optional.empty(), RecurringStatusFilter.ALL, 25), t));

        Map<String, String> payloads = send(tools);

        payloads.forEach((name, payload) -> assertThat(json.readTree(payload)).as(name).isEqualTo(expected(name)));
        assertThat(json.readTree(payloads.get("analytics-get_alerts")).get("items").valueStream()
                .map(item -> item.get("type").asString()).distinct())
                .as("one of each detail type").hasSize(5);
    }

    @Test
    void noHostileTextNoCanaryNoIdentifierAndNoIdReachesAnyPayload() throws Exception {
        new ChatInjectionFixture(support, jdbc, json).load();

        String all = String.join("\n", injectionPayloads().values());

        assertThat(HOSTILE).allSatisfy(word -> assertThat(all.toUpperCase()).doesNotContain(word));
        assertThat(ChatInjectionFixture.hostileDescriptions()).hasSize(7);
        for (String description : ChatInjectionFixture.hostileDescriptions()) {
            assertThat(all).doesNotContain(description);
        }
        assertThat(all).doesNotContain(ChatInjectionFixture.NAME).doesNotContain(ChatInjectionFixture.INSTITUTION)
                .doesNotContain("Halden").doesNotContain(ChatInjectionFixture.LAST_FOUR);
        assertThat(all).doesNotContain("\"id\"", "\"accountId\"", "\"statementId\"", "\"key\"", "\"categoryId\"",
                "\"description\"", "\"sourceLine", "\"counterparty\"", "\"netChange\"", "\"name\"");
        assertThat(all).contains("MERCHANT WITHHELD");
    }

    @Test
    void theLogCountsWithheldNamesAndNothingElse(CapturedOutput output) throws Exception {
        new ChatInjectionFixture(support, jdbc, json).load();

        injectionPayloads();

        assertThat(output.getOut())
                .contains("chat outbound tool=list_transactions rows=13 droppedRows=0 withheldMerchants=7 "
                        + "refusedMerchants=0")
                .contains("chat outbound tool=get_top_merchants rows=10 droppedRows=0 withheldMerchants=7 "
                        + "refusedMerchants=0");
        List<String> lines = output.getOut().lines().filter(line -> line.contains("chat outbound")).toList();
        assertThat(lines).hasSize(11).allSatisfy(line -> {
            for (String word : HOSTILE) {
                assertThat(line.toUpperCase()).doesNotContain(word);
            }
            assertThat(line).doesNotContain("MAPLEWOOD").doesNotContain("HOLLOWAY");
        });
    }

    private tools.jackson.databind.JsonNode expected(String name) {
        try {
            return json.readTree(Files.readString(EXPECTED.resolve(name + ".json")));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
