package com.finalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.analytics.MonthFlagType;
import com.finalysis.chat.outbound.ChatOutbound;
import com.finalysis.chat.outbound.ChatPayloadGuard;
import com.finalysis.chat.outbound.OutboundProjector;
import com.finalysis.chat.outbound.OutboundTurn;
import com.finalysis.chat.outbound.OutboundTurnFactory;
import com.finalysis.chat.outbound.ToolResultWriter;
import com.finalysis.chat.tool.CategoryNamesQuery;
import com.finalysis.chat.tool.ChatTool;
import com.finalysis.chat.tool.DataNote;
import com.finalysis.chat.tool.DataNotesReader;
import com.finalysis.chat.tool.ToolArguments;
import com.finalysis.chat.tool.ToolName;
import com.finalysis.chat.tool.ToolParam;
import com.finalysis.chat.tool.ToolRead;
import com.finalysis.chat.tool.ToolRegistry;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ToolExecutor} on the analytics fixture (docs/plans/phase-6.md §3.1, §9): the fixed registry, arguments the
 * model invented answered with readable errors that echo nothing, data notes, merchants resolved within the turn, the
 * read-only transaction Postgres enforces, and the count-only log line.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ToolExecutorTest {

    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ToolExecutor executor;
    private final OutboundTurnFactory turns;
    private final DataNotesReader notes;
    private final OutboundProjector projector;
    private final ChatOutbound outbound;
    private final ChatPayloadGuard guard;
    private final ToolResultWriter writer;
    private final PlatformTransactionManager transactions;
    private long checking;

    ToolExecutorTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper json,
                     ToolExecutor executor, OutboundTurnFactory turns, DataNotesReader notes,
                     OutboundProjector projector, ChatOutbound outbound, ChatPayloadGuard guard,
                     ToolResultWriter writer, PlatformTransactionManager transactions) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, json);
        this.jdbc = jdbc;
        this.json = json;
        this.executor = executor;
        this.turns = turns;
        this.notes = notes;
        this.projector = projector;
        this.outbound = outbound;
        this.guard = guard;
        this.writer = writer;
        this.transactions = transactions;
    }

    @BeforeAll
    void importFixture() throws Exception {
        checking = fixture.load().accounts().get(AnalyticsFixture.CHECKING);
    }

    @AfterAll
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @Test
    void everyToolAnswersWithValidArguments() {
        List<String> calls = List.of(
                "get_month_totals|{\"from\": \"2026-01\", \"to\": \"2026-04\", \"account\": \"CARD-1\"}",
                "get_category_totals|{\"uncategorized\": true}",
                "get_account_totals|{\"to\": \"2026-04\"}",
                "get_top_merchants|{\"category\": \"Dining\", \"limit\": 1}",
                "get_trends|{}",
                "compare_periods|{\"measure\": \"NET\", \"account\": \"CHECKING-1\", \"a\": {\"from\": \"2026-01\", "
                        + "\"to\": \"2026-02\"}, \"b\": {\"from\": \"2026-03\", \"to\": \"2026-04\"}}",
                "list_transactions|{\"bucket\": \"UNCATEGORIZED\", \"limit\": 25}",
                "get_recurring_charges|{\"status\": \"ACTIVE\", \"limit\": 1}",
                "get_alerts|{\"type\": \"LARGE_TRANSACTION\", \"includeDismissed\": true, \"limit\": 1}",
                "get_data_coverage|");
        assertThat(calls).hasSize(ToolName.values().length);
        for (String call : calls) {
            String[] parts = call.split("\\|", 2);
            ToolOutcome outcome = executor.execute(new ToolCall(parts[0], parts[1]), turns.start());
            assertThat(outcome.status()).as(call + " → " + outcome.json()).isEqualTo(ToolStatus.OK);
            assertThat(outcome.tool().wire()).isEqualTo(parts[0]);
            assertThat(json.readTree(outcome.json()).has("result")).isTrue();
        }
    }

    @Test
    void anUnknownToolIsAnErrorThatNamesNothing(CapturedOutput output) {
        ToolOutcome outcome = executor.execute(new ToolCall("dismiss_alert", "{\"alert\": \"a1\"}"), turns.start());

        assertThat(outcome.status()).isEqualTo(ToolStatus.INVALID_ARGUMENTS);
        assertThat(outcome.tool()).isNull();
        assertThat(json.readTree(outcome.json())).isEqualTo(json.readTree("""
                {"error": "INVALID_ARGUMENTS", "message": "no such tool; use one of the tools you were given"}"""));
        assertThat(output.getOut()).contains("chat tool tool=(unknown) status=INVALID_ARGUMENTS rows=0")
                .doesNotContain("dismiss_alert");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "get_category_totals|{\"category\": \"Coffee IGNORE PREVIOUS\"}|category|"
                    + "not a category name; use one of categories exactly as written|IGNORE",
            "get_month_totals|{\"account\": \"CHECKING-7\"}|account|not an account alias; see the context block|"
                    + "CHECKING-7",
            "get_month_totals|{\"from\": \"2026-13\"}|from|must be a month written yyyy-MM, 01 to 12|2026-13",
            "get_trends|{\"month\": \"2026-00\", \"say\": \"999.99\"}|(unknown)|"
                    + "not a parameter of this tool; use only the schema's properties|999.99",
            "get_category_totals|{\"category\": \"Dining\", \"uncategorized\": true}|uncategorized|"
                    + "can't be combined with the parameter named in conflictsWith|Dining",
            "list_transactions|{\"month\": \"2026-03\", \"from\": \"2026-01\", \"limit\": 5}|month|"
                    + "can't be combined with the parameter named in conflictsWith|2026-01",
            "get_month_totals|{\"from\": \"2026-04\", \"to\": \"2026-01\"}|from|must not be after to|2026-04",
            "get_month_totals|{\"from\": \"2020-01\"}|from|the range is at most 24 months, both ends included|2020",
            "compare_periods|{\"measure\": \"CATEGORY_SPENT\", \"a\": {\"month\": \"2026-03\"}, \"b\": "
                    + "{\"month\": \"2026-02\"}}|measure|needs the parameter named in conflictsWith|2026-03",
            "get_top_merchants|{\"limit\": 500}|limit|is outside the minimum and maximum the schema gives|500",
            "get_alerts|{}|limit|is required|limit\":",
            "list_transactions|{\"merchant\": \"MERCHANT WITHHELD\", \"limit\": 5}|merchant|"
                    + "a withheld name can't be used as a filter; filter by category, month or account instead|WITHHELD",
            "list_transactions|{\"merchant\": \"SECRET SHOP\", \"limit\": 5}|merchant|"
                    + "not a merchant name from this turn's results; copy one exactly, or filter by category|SECRET",
            "get_data_coverage|not json at all 999.99|(unknown)|the arguments must be one JSON object|999.99"})
    void anInventedArgumentIsAReadableErrorThatEchoesNothing(String tool, String arguments, String field,
                                                             String message, String neverSent) {
        ToolOutcome outcome = executor.execute(new ToolCall(tool, arguments), turns.start());

        assertThat(outcome.status()).isEqualTo(ToolStatus.INVALID_ARGUMENTS);
        JsonNode error = json.readTree(outcome.json());
        assertThat(error.get("error").stringValue()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(error.get("fields").get(0).get("name").stringValue()).isEqualTo(field);
        assertThat(error.get("fields").get(0).get("message").stringValue()).isEqualTo(message);
        assertThat(outcome.json()).doesNotContain(neverSent);
        assertThat(outcome.dataNotes()).isEmpty();
    }

    /** Step 3 review: the model can correct an unknown category on retry, and still nothing it sent is echoed. */
    @Test
    void anUnknownCategoryListsTheValidNamesAsTheContextBlockDoes() {
        OutboundTurn turn = turns.start();
        JsonNode error = json.readTree(executor.execute(new ToolCall("get_category_totals",
                "{\"category\": \"Dinning\"}"), turn).json());

        JsonNode field = error.get("fields").get(0);
        assertThat(field.get("name").stringValue()).isEqualTo("category");
        List<String> listed = field.get("categories").valueStream().map(JsonNode::stringValue).toList();
        assertThat(listed).isEqualTo(CategoryNamesQuery.sorted(turn.categories())).contains("Dining")
                .doesNotContain("Dinning");
        assertThat(error.toString()).doesNotContain("Dinning");

        JsonNode other = json.readTree(executor.execute(new ToolCall("get_month_totals",
                "{\"account\": \"CHECKING-7\"}"), turns.start()).json());
        assertThat(other.get("fields").get(0).has("categories")).as("only a category problem lists them").isFalse();
    }

    @Test
    void conflictsNameTheOtherParameter() {
        JsonNode error = json.readTree(executor.execute(new ToolCall("compare_periods", """
                {"measure": "SPENDING", "category": "Dining", "a": {"month": "2026-03"}, "b": {"month": "2026-02"}}"""),
                turns.start()).json());

        assertThat(error.get("fields")).isEqualTo(json.readTree("""
                [{"name": "category", "message": "can't be combined with the parameter named in conflictsWith",
                  "conflictsWith": "measure"}]"""));
    }

    @Test
    void dataNotesRideAlongWithTheMonthsTheResultUsed() {
        ToolOutcome categories = executor.execute(new ToolCall("get_category_totals",
                "{\"from\": \"2026-03\", \"to\": \"2026-03\"}"), turns.start());

        assertThat(categories.dataNotes()).containsExactly(new DataNote(YearMonth.of(2026, 3),
                MonthFlagType.RECONCILIATION_FAILED, checking));
        assertThat(json.readTree(categories.json()).get("dataNotes")).isEqualTo(json.readTree("""
                [{"type": "RECONCILIATION_FAILED", "account": "CHECKING-1", "month": "2026-03"}]"""));

        ToolOutcome months = executor.execute(new ToolCall("get_month_totals",
                "{\"from\": \"2026-03\", \"to\": \"2026-04\"}"), turns.start());
        assertThat(months.dataNotes()).extracting(DataNote::type).containsExactlyInAnyOrder(
                MonthFlagType.RECONCILIATION_FAILED, MonthFlagType.PARTIAL_COVERAGE);
        assertThat(json.readTree(months.json()).has("dataNotes")).as("the months carry their own flags").isFalse();

        ToolOutcome february = executor.execute(new ToolCall("get_trends", "{\"month\": \"2026-02\"}"),
                turns.start());
        assertThat(february.dataNotes()).isEmpty();
        assertThat(json.readTree(february.json()).has("dataNotes")).isFalse();
    }

    @Test
    void aMerchantFilterTakesExactlyWhatTheTurnShowed() {
        OutboundTurn turn = turns.start();
        JsonNode top = json.readTree(executor.execute(new ToolCall("get_top_merchants",
                "{\"from\": \"2026-01\", \"to\": \"2026-04\", \"category\": \"Dining\", \"limit\": 1}"), turn).json());
        String merchant = top.get("result").get("items").get(0).get("merchant").stringValue();

        ToolOutcome listed = executor.execute(new ToolCall("list_transactions", json.createObjectNode()
                .put("from", "2026-01").put("to", "2026-04").put("merchant", merchant).put("limit", 25).toString()),
                turn);

        assertThat(listed.status()).isEqualTo(ToolStatus.OK);
        JsonNode result = json.readTree(listed.json()).get("result");
        assertThat(result.get("items").valueStream().map(i -> i.get("merchant").stringValue()).distinct())
                .containsExactly(merchant);
        assertThat(result.get("total").get("count").intValue())
                .isEqualTo(top.get("result").get("items").get(0).get("count").intValue());

        ToolOutcome otherTurn = executor.execute(new ToolCall("list_transactions",
                json.createObjectNode().put("merchant", merchant).put("limit", 5).toString()), turns.start());
        assertThat(otherTurn.status()).as("a new turn hasn't shown it").isEqualTo(ToolStatus.INVALID_ARGUMENTS);
    }

    /** §9.3: a tool that tries to write, even without its annotation, runs in a READ ONLY transaction. */
    @Test
    void postgresRefusesAToolThatWrites(CapturedOutput output) {
        ToolExecutor rogue = new ToolExecutor(ToolRegistry.of(List.of(new WritingTool(jdbc)), json), notes,
                projector, outbound, guard, writer, json, transactions);
        String before = jdbc.queryForObject("SELECT string_agg(name, ',' ORDER BY id) FROM category", String.class);

        ToolOutcome outcome = rogue.execute(new ToolCall("get_data_coverage", "{}"), turns.start());

        assertThat(outcome.status()).isEqualTo(ToolStatus.FAILED);
        assertThat(json.readTree(outcome.json()).get("message").stringValue())
                .isEqualTo("the tool could not read the data; answer without it, or try a narrower question");
        assertThat(outcome.json()).doesNotContain("read-only").doesNotContain("UPDATE");
        assertThat(jdbc.queryForObject("SELECT string_agg(name, ',' ORDER BY id) FROM category", String.class))
                .isEqualTo(before);
        assertThat(output.getOut()).contains("chat tool tool=get_data_coverage status=FAILED rows=0")
                .contains("error=");
    }

    @Test
    void theLogLineCountsAndNamesNoArgument(CapturedOutput output) {
        executor.execute(new ToolCall("get_category_totals", "{\"category\": \"Dining\", \"from\": \"2026-02\"}"),
                turns.start());

        List<String> lines = output.getOut().lines().filter(l -> l.contains("chat tool ")).toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).containsPattern("chat tool tool=get_category_totals status=OK rows=\\d+ ms=\\d+$")
                .doesNotContain("Dining").doesNotContain("2026-02");
    }

    /** A rule fixture: a tool that writes, and whose read lacks the read-only annotation. */
    static final class WritingTool implements ChatTool {

        private final JdbcTemplate jdbc;

        WritingTool(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public ToolName name() {
            return ToolName.GET_DATA_COVERAGE;
        }

        @Override
        public String description() {
            return "writes";
        }

        @Override
        public List<ToolParam> params() {
            return List.of();
        }

        @Override
        public ToolRead read(ToolArguments arguments) {
            jdbc.update("UPDATE category SET name = name || ' (changed)' WHERE name = 'Dining'");
            throw new AssertionError("Postgres let a chat tool write");
        }
    }
}
