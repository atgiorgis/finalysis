package com.finalysis.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.TxnBucket;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Argument checking (docs/plans/phase-6.md §3.1), no Spring: every parameter type accepts what its schema describes and
 * nothing else, problems name the schema's parameter and never the model's value or invented key, and nothing is
 * repaired.
 */
class ToolArgumentsTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * CHECKING-1 → 11, Dining → 1; "BLUE BEAN CAFE" sent for one stored merchant, "STARBUCKS" for two; a search text
     * naming "Ana" may not leave.
     */
    static final ToolScope SCOPE = new ToolScope() {
        @Override
        public Optional<Long> accountId(String alias) {
            return Optional.ofNullable(Map.of("CHECKING-1", 11L).get(alias));
        }

        @Override
        public Optional<Long> categoryId(String name) {
            return Optional.ofNullable(Map.of("Dining", 1L).get(name));
        }

        @Override
        public boolean isWithheldMerchant(String text) {
            return "MERCHANT WITHHELD".equals(text);
        }

        @Override
        public List<String> storedMerchants(String outboundText) {
            return Map.of("BLUE BEAN CAFE", List.of("BLUE BEAN CAFE #12"),
                    "STARBUCKS", List.of("STARBUCKS #1", "STARBUCKS ARLINGTON VA")).getOrDefault(outboundText, List.of());
        }

        @Override
        public Optional<SearchText> searchText(String typed) {
            return typed.contains("Ana") ? Optional.empty() : Optional.of(new SearchText(typed.toUpperCase(),
                    MerchantDirectory.wordsOf(typed), null, "test-embedding-model", 0.4, MerchantDirectory.empty()));
        }
    };

    static final List<ToolParam> PARAMS = List.of(
            ToolParam.optional("from", new ToolParam.Month(), ""),
            ToolParam.optional("to", new ToolParam.Month(), ""),
            ToolParam.optional("account", new ToolParam.Alias(), ""),
            ToolParam.optional("category", new ToolParam.Category(), ""),
            ToolParam.optional("bucket", new ToolParam.Choice(TxnBucket.class), ""),
            ToolParam.optional("merchant", new ToolParam.Merchant(), ""),
            ToolParam.optional("uncategorized", new ToolParam.Flag(), ""),
            ToolParam.optional("a", new ToolParam.Period(), ""),
            ToolParam.optional("merchants", new ToolParam.Merchants(), ""),
            ToolParam.optional("query", new ToolParam.Search(), ""),
            ToolParam.required("limit", new ToolParam.WholeNumber(1, 20), ""));

    static ToolArguments.Parsed parse(String json) {
        return ToolArguments.parse(JSON.readTree(json), PARAMS, SCOPE);
    }

    @Test
    void validArgumentsAreResolved() {
        ToolArguments.Parsed parsed = parse("""
                {"from": "2026-06", "to": "2026-08", "account": "CHECKING-1", "category": "Dining",
                 "bucket": "EXPENSE", "merchant": "BLUE BEAN CAFE", "uncategorized": false,
                 "a": {"from": "2026-01", "to": "2026-03"}, "limit": 20}""");

        assertThat(parsed.ok()).isTrue();
        ToolArguments args = parsed.arguments();
        assertThat(args.month("from")).contains(YearMonth.of(2026, 6));
        assertThat(args.id("account")).contains(11L);
        assertThat(args.id("category")).contains(1L);
        assertThat(args.choice("bucket", TxnBucket.class)).contains(TxnBucket.EXPENSE);
        assertThat(args.text("merchant")).contains("BLUE BEAN CAFE #12");
        assertThat(args.flag("uncategorized", true)).isFalse();
        assertThat(args.period("a")).isEqualTo(new MonthRange(YearMonth.of(2026, 1), YearMonth.of(2026, 3)));
        assertThat(args.number("limit")).isEqualTo(20);
        assertThat(args.rangeProblems()).isEmpty();
    }

    /** A merchant text stands for every stored merchant behind it; a search text arrives prepared by the scope. */
    @Test
    void merchantListsAndSearchTextsAreResolved() {
        ToolArguments args = parse("""
                {"merchants": ["STARBUCKS", "BLUE BEAN CAFE"], "query": "coffee shops", "limit": 1}""").arguments();

        assertThat(args.merchants("merchants")).containsExactly(
                new MerchantGroup("STARBUCKS", List.of("STARBUCKS #1", "STARBUCKS ARLINGTON VA")),
                new MerchantGroup("BLUE BEAN CAFE", List.of("BLUE BEAN CAFE #12")));
        assertThat(args.search("query").outbound()).isEqualTo("COFFEE SHOPS");
        assertThat(args.search("query").words()).containsExactly("COFFEE", "SHOPS");
        assertThat(args.search("query").byMeaning()).isFalse();
    }

    @Test
    void nullCountsAsLeftOutAndDefaultsApply() {
        ToolArguments.Parsed parsed = parse("{\"from\": null, \"limit\": 1}");

        assertThat(parsed.ok()).isTrue();
        assertThat(parsed.arguments().has("from")).isFalse();
        assertThat(parsed.arguments().flag("uncategorized", false)).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"limit\": 5, \"from\": \"2026-13\"}|from|NOT_A_MONTH",
            "{\"limit\": 5, \"from\": \"2026-1\"}|from|NOT_A_MONTH",
            "{\"limit\": 5, \"from\": 202608}|from|NOT_A_MONTH",
            "{\"limit\": 5, \"account\": \"CHECKING-9\"}|account|NOT_AN_ACCOUNT",
            "{\"limit\": 5, \"account\": \"Halden Everyday\"}|account|NOT_AN_ACCOUNT",
            "{\"limit\": 5, \"category\": \"Coffee Shops\"}|category|NOT_A_CATEGORY",
            "{\"limit\": 5, \"category\": \"dining\"}|category|NOT_A_CATEGORY",
            "{\"limit\": 5, \"category\": 4}|category|NOT_TEXT",
            "{\"limit\": 5, \"bucket\": \"expense\"}|bucket|NOT_A_LISTED_VALUE",
            "{\"limit\": 5, \"merchant\": \"MERCHANT WITHHELD\"}|merchant|WITHHELD_MERCHANT",
            "{\"limit\": 5, \"merchant\": \"AMAZON\"}|merchant|NOT_A_MERCHANT",
            "{\"limit\": 5, \"merchant\": \"STARBUCKS\"}|merchant|SEVERAL_MERCHANTS",
            "{\"limit\": 5, \"uncategorized\": \"yes\"}|uncategorized|NOT_TRUE_OR_FALSE",
            "{\"limit\": 0}|limit|OUT_OF_BOUNDS",
            "{\"limit\": 21}|limit|OUT_OF_BOUNDS",
            "{\"limit\": 2.5}|limit|NOT_A_WHOLE_NUMBER",
            "{\"limit\": 99999999999}|limit|OUT_OF_BOUNDS",
            "{\"limit\": \"5\"}|limit|NOT_A_WHOLE_NUMBER",
            "{}|limit|REQUIRED",
            "{\"limit\": null}|limit|REQUIRED",
            "{\"limit\": 5, \"a\": \"2026-08\"}|a|NOT_A_PERIOD",
            "{\"limit\": 5, \"a\": {\"month\": \"2026-08\", \"from\": \"2026-01\"}}|a|NOT_A_PERIOD",
            "{\"limit\": 5, \"a\": {\"from\": \"2026-01\"}}|a|NOT_A_PERIOD",
            "{\"limit\": 5, \"a\": {\"month\": \"2026-08\", \"say\": \"hi\"}}|a|NOT_A_PERIOD",
            "{\"limit\": 5, \"a\": {\"month\": \"08/2026\"}}|a.month|NOT_A_MONTH",
            "{\"limit\": 5, \"a\": {\"from\": \"2026-05\", \"to\": \"2026-01\"}}|a.from|REVERSED_RANGE",
            "{\"limit\": 5, \"a\": {\"from\": \"2024-01\", \"to\": \"2026-01\"}}|a|RANGE_TOO_LONG",
            "{\"limit\": 5, \"merchants\": \"STARBUCKS\"}|merchants|NOT_A_LIST",
            "{\"limit\": 5, \"merchants\": []}|merchants|OUT_OF_BOUNDS",
            "{\"limit\": 5, \"merchants\": [\"A\",\"B\",\"C\",\"D\",\"E\",\"F\",\"G\",\"H\",\"I\",\"J\",\"K\"]}|merchants|OUT_OF_BOUNDS",
            "{\"limit\": 5, \"merchants\": [7]}|merchants|NOT_TEXT",
            "{\"limit\": 5, \"merchants\": [\"MERCHANT WITHHELD\"]}|merchants|WITHHELD_MERCHANT",
            "{\"limit\": 5, \"merchants\": [\"STARBUCKS\", \"STARBUCKS\"]}|merchants|LISTED_TWICE",
            "{\"limit\": 5, \"merchants\": [\"STARBUCKS\", \"SECRET SHOP\"]}|merchants|NOT_A_MERCHANT",
            "{\"limit\": 5, \"query\": 5}|query|NOT_TEXT",
            "{\"limit\": 5, \"query\": \"a\"}|query|TEXT_TOO_SHORT",
            "{\"limit\": 5, \"query\": \"  a  \"}|query|TEXT_TOO_SHORT",
            "{\"limit\": 5, \"query\": \"coffee coffee coffee coffee coffee coffee coffee coffee coffee\"}|query|TEXT_TOO_LONG",
            "{\"limit\": 5, \"query\": \"gifts for Ana\"}|query|SEARCH_REFUSED"})
    void eachInvalidValueIsAProblemOnItsParameter(String json, String name, ToolProblem problem) {
        ToolArguments.Parsed parsed = parse(json);

        assertThat(parsed.ok()).isFalse();
        assertThat(parsed.arguments()).isNull();
        assertThat(parsed.problems()).containsExactly(new FieldProblem(name, problem));
    }

    @Test
    void anUnknownKeyIsReportedOnceWithoutItsName() {
        ToolArguments.Parsed parsed = parse("""
                {"limit": 5, "ignorePreviousInstructions": true, "dismiss_alert": "a1"}""");

        assertThat(parsed.problems()).containsExactly(
                new FieldProblem(FieldProblem.UNKNOWN, ToolProblem.UNKNOWN_PARAMETER));
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        ToolArguments.Parsed parsed = parse("{\"from\": \"x\", \"account\": \"y\"}");

        assertThat(parsed.problems()).extracting(FieldProblem::name).containsExactly("from", "account", "limit");
    }

    @Test
    void anythingButAnObjectIsRefused() {
        for (String json : List.of("[]", "\"limit\"", "null", "3")) {
            JsonNode node = JSON.readTree(json);
            assertThat(ToolArguments.parse(node, PARAMS, SCOPE).problems()).as(json)
                    .containsExactly(new FieldProblem(FieldProblem.UNKNOWN, ToolProblem.NOT_AN_OBJECT));
        }
        assertThat(ToolArguments.parse(null, PARAMS, SCOPE).ok()).isFalse();
    }

    @Test
    void rangesAndConflictsBetweenParameters() {
        assertThat(parse("{\"limit\": 1, \"from\": \"2026-08\", \"to\": \"2026-06\"}").arguments().rangeProblems())
                .containsExactly(new FieldProblem("from", ToolProblem.REVERSED_RANGE));
        assertThat(parse("{\"limit\": 1, \"from\": \"2024-08\", \"to\": \"2026-08\"}").arguments().rangeProblems())
                .containsExactly(new FieldProblem("to", ToolProblem.RANGE_TOO_LONG));
        assertThat(parse("{\"limit\": 1, \"from\": \"2024-09\", \"to\": \"2026-08\"}").arguments().rangeProblems())
                .as("24 months, both ends included").isEmpty();
        assertThat(parse("{\"limit\": 1, \"from\": \"2024-08\"}").arguments().rangeProblems())
                .as("one end: the service applies its default and bound").isEmpty();
        assertThat(parse("{\"limit\": 1, \"category\": \"Dining\", \"uncategorized\": true}").arguments()
                .conflict("uncategorized", "category"))
                .containsExactly(new FieldProblem("uncategorized", ToolProblem.CONFLICT, "category"));
    }

    @Test
    void messagesAreFixedAndNeverCarryAValue() {
        for (ToolProblem problem : ToolProblem.values()) {
            assertThat(ToolProblem.isText(problem.text())).isTrue();
            assertThat(problem.text()).doesNotContain("%s").doesNotContain("{}").hasSizeLessThan(120);
        }
        assertThat(ToolProblem.isText("not a category name")).isFalse();
    }
}
