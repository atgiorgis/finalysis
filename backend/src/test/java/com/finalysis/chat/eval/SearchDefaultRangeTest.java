package com.finalysis.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.chat.ToolCall;
import com.finalysis.chat.ToolExecutor;
import com.finalysis.chat.ToolOutcome;
import com.finalysis.chat.ToolStatus;
import com.finalysis.chat.eval.ChatEvalSet.ExpectedFigure;
import com.finalysis.chat.outbound.OutboundTurn;
import com.finalysis.chat.outbound.OutboundTurnFactory;
import com.finalysis.support.ChatIntegrationTest;
import com.finalysis.support.ImportTestSupport;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 8c: {@code search_transactions} and {@code get_merchant_totals} without a period cover every month with data,
 * the dev set's d-s04 and d-s08 being the cases that showed it. On the sample data, seeded as the evaluation seeds it,
 * with the dev set's own expected figures (checked against the answer key by {@code ChatQuestionSetsTest}): the
 * pharmacy over all data is 175.85 (CVS, six rows, 2026-04-16 to 2026-09-16), and the internet bill 399.95 (SKYWAVE,
 * five bills); the data runs from March to October 2026. The analytics default of 6 months, May to October, gave
 * 162.31 and 319.96 in the first dev run.
 */
@ChatIntegrationTest
@AutoConfigureMockMvc
@Import(ChatEvalBeans.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchDefaultRangeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ChatEvalSeeder seeder;
    private final ToolExecutor executor;
    private final OutboundTurnFactory turns;
    private ChatEvalSet dev;

    SearchDefaultRangeTest(ChatEvalSeeder seeder, ToolExecutor executor, OutboundTurnFactory turns) {
        this.seeder = seeder;
        this.executor = executor;
        this.turns = turns;
    }

    @BeforeAll
    void seed() throws Exception {
        dev = ChatEvalSet.load(ImportTestSupport.SAMPLE_DATA.resolve("chat").resolve("dev.yml"));
        seeder.seed("SAMPLE");
    }

    @AfterAll
    void clear() {
        seeder.clear();
    }

    /** The dev set's figures for a question: every alternative's, as written. */
    private List<BigDecimal> expected(String id) {
        return dev.question(id).turns().getFirst().figures().stream().flatMap(List::stream)
                .map(ExpectedFigure::value).toList();
    }

    private JsonNode result(OutboundTurn turn, String tool, String arguments) {
        ToolOutcome outcome = executor.execute(new ToolCall(tool, arguments), turn);
        assertThat(outcome.status()).as(tool + " " + outcome.json()).isEqualTo(ToolStatus.OK);
        return JSON.readTree(outcome.json()).get("result");
    }

    /** A search's result: its candidates and the range it says it covered. */
    private JsonNode byName(OutboundTurn turn, String query, String arguments) {
        return result(turn, "search_transactions", "{\"query\": \"" + query + "\", \"limit\": 5" + arguments + "}");
    }

    private static JsonNode nameMatch(JsonNode search) {
        return search.get("candidates").valueStream().filter(c -> c.get("match").stringValue().equals("NAME"))
                .findFirst().orElseThrow();
    }

    @Test
    void thePharmacyWithoutAPeriodIsOverAllData() {
        OutboundTurn turn = turns.start();
        JsonNode search = byName(turn, "CVS", "");
        JsonNode cvs = nameMatch(search);

        assertThat(expected("d-s04")).containsExactly(new BigDecimal("175.85"));
        assertThat(new BigDecimal(cvs.get("spent").stringValue())).isEqualByComparingTo("175.85");
        assertThat(cvs.get("count").asInt()).isEqualTo(6);
        assertThat(search.get("from").stringValue()).isEqualTo("2026-03");
        assertThat(search.get("to").stringValue()).isEqualTo("2026-10");

        JsonNode totals = result(turn, "get_merchant_totals", "{\"merchants\": [\""
                + cvs.get("merchant").stringValue() + "\"]}");
        assertThat(new BigDecimal(totals.get("combined").get("spent").stringValue())).isEqualByComparingTo("175.85");
        assertThat(totals.get("from").stringValue()).isEqualTo("2026-03");
        assertThat(totals.get("to").stringValue()).isEqualTo("2026-10");

        // the first dev run's figure: the analytics default, 6 months ending at the latest month
        JsonNode sixMonths = nameMatch(byName(turns.start(), "CVS", ", \"from\": \"2026-05\", \"to\": \"2026-10\""));
        assertThat(new BigDecimal(sixMonths.get("spent").stringValue())).isEqualByComparingTo("162.31");
    }

    @Test
    void theInternetBillWithoutAPeriodIsOverAllData() {
        OutboundTurn turn = turns.start();
        JsonNode skywave = nameMatch(byName(turn, "SKYWAVE", ""));

        assertThat(expected("d-s08")).contains(new BigDecimal("399.95"));
        assertThat(new BigDecimal(skywave.get("spent").stringValue())).isEqualByComparingTo("399.95");
        assertThat(skywave.get("count").asInt()).isEqualTo(5);

        JsonNode totals = result(turn, "get_merchant_totals", "{\"merchants\": [\""
                + skywave.get("merchant").stringValue() + "\"]}");
        assertThat(new BigDecimal(totals.get("combined").get("spent").stringValue())).isEqualByComparingTo("399.95");
    }

    /**
     * Step 8f: with no period, every merchant tool covers every month with data, so one merchant has one count. Dev run
     * 4's d-s05: get_top_merchants said 17 for CHIPOTLE (its 6-month default, May to October) where search and
     * get_merchant_totals said 18 (one row is in April).
     */
    @Test
    void everyMerchantToolGivesOneCountWithoutAPeriod() {
        OutboundTurn turn = turns.start();
        JsonNode chipotle = nameMatch(byName(turn, "CHIPOTLE", ""));
        String merchant = chipotle.get("merchant").stringValue();

        JsonNode totals = result(turn, "get_merchant_totals", "{\"merchants\": [\"" + merchant + "\"]}");
        JsonNode top = result(turn, "get_top_merchants", "{\"limit\": 20}");
        JsonNode topRow = top.get("items").valueStream()
                .filter(m -> merchant.equals(m.path("merchant").stringValue(null))).findFirst().orElseThrow();
        JsonNode listed = result(turn, "list_transactions", "{\"limit\": 5, \"merchant\": \"" + merchant + "\"}");

        assertThat(chipotle.get("count").asInt()).isEqualTo(18);
        assertThat(totals.get("combined").get("count").asInt()).isEqualTo(18);
        assertThat(topRow.get("count").asInt()).isEqualTo(18);
        assertThat(listed.get("total").get("count").asInt()).isEqualTo(18);
        assertThat(new BigDecimal(topRow.get("spent").stringValue()))
                .isEqualByComparingTo(new BigDecimal(chipotle.get("spent").stringValue()));
        for (JsonNode result : List.of(top, listed)) {
            assertThat(result.get("from").stringValue()).isEqualTo("2026-03");
            assertThat(result.get("to").stringValue()).isEqualTo("2026-10");
        }

        // a period the model gives still wins: May to October has the 17 of the old default
        JsonNode mayOn = result(turn, "get_top_merchants", "{\"limit\": 20, \"from\": \"2026-05\"}");
        assertThat(mayOn.get("items").valueStream().filter(m -> merchant.equals(m.path("merchant").stringValue(null)))
                .findFirst().orElseThrow().get("count").asInt()).isEqualTo(17);
        assertThat(result(turn, "list_transactions", "{\"limit\": 5, \"merchant\": \"" + merchant
                + "\", \"month\": \"2026-04\"}")
                .get("total").get("count").asInt()).isEqualTo(1);
    }

    @Test
    void aMonthIsStillOneMonthAndAToStillEndsTheRange() {
        OutboundTurn turn = turns.start();
        JsonNode august = byName(turn, "SKYWAVE", ", \"month\": \"2026-08\"");
        assertThat(august.get("from").stringValue()).isEqualTo("2026-08");
        assertThat(august.get("to").stringValue()).isEqualTo("2026-08");
        assertThat(new BigDecimal(nameMatch(august).get("spent").stringValue())).isEqualByComparingTo("79.99");

        // to without from: from the first month with data up to to
        JsonNode toJune = byName(turns.start(), "CVS", ", \"to\": \"2026-06\"");
        assertThat(toJune.get("from").stringValue()).isEqualTo("2026-03");
        assertThat(toJune.get("to").stringValue()).isEqualTo("2026-06");
    }

    @Test
    void aSearchThatFindsNothingStillSaysWhichMonthsItCovered() {
        JsonNode none = result(turns.start(), "search_transactions", "{\"query\": \"zeppelin rides\", \"limit\": 5}");
        assertThat(none.get("candidates").isEmpty()).isTrue();
        assertThat(none.get("from").stringValue()).isEqualTo("2026-03");
        assertThat(none.get("to").stringValue()).isEqualTo("2026-10");
    }
}
