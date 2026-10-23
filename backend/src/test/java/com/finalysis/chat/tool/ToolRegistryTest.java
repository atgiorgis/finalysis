package com.finalysis.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fixed registry and its schemas (docs/plans/phase-6.md §3.1, §3.2, §9), no Spring: one tool per name, the
 * parameters of §3.2, closed schemas with required lists, enums, month patterns and maximums.
 */
class ToolRegistryTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    /** The tools without their services: names, descriptions and parameters need none. */
    static List<ChatTool> tools() {
        return List.of(new MonthTotalsTool(null), new CategoryTotalsTool(null), new AccountTotalsTool(null),
                new TopMerchantsTool(null, null), new TrendsTool(null), new ComparePeriodsTool(null),
                new ListTransactionsTool(null, null), new RecurringChargesTool(null), new AlertsTool(null, null),
                new DataCoverageTool(null), new SearchTransactionsTool(null, null, null, null), new MerchantTotalsTool(null, null));
    }

    /** §3.2's parameters per tool, with step 8b's {@code month} on every range tool; required ones marked "!". */
    static final Map<String, List<String>> PLAN = new LinkedHashMap<>();

    static {
        PLAN.put("get_month_totals", List.of("month", "from", "to", "account"));
        PLAN.put("get_category_totals", List.of("month", "from", "to", "account", "category", "uncategorized"));
        PLAN.put("get_account_totals", List.of("month", "from", "to"));
        PLAN.put("get_top_merchants", List.of("month", "from", "to", "account", "category", "limit!"));
        PLAN.put("get_trends", List.of("month", "account", "category"));
        PLAN.put("compare_periods", List.of("measure!", "category", "account", "a!", "b!"));
        PLAN.put("list_transactions", List.of("month", "from", "to", "bucket", "measure", "account", "category",
                "merchant", "limit!"));
        PLAN.put("get_recurring_charges", List.of("account", "status!", "limit!"));
        PLAN.put("get_alerts", List.of("month", "from", "to", "type", "account", "includeDismissed", "limit!"));
        PLAN.put("get_data_coverage", List.of());
        PLAN.put("search_transactions", List.of("query!", "month", "from", "to", "limit!"));
        PLAN.put("get_merchant_totals", List.of("merchants!", "month", "from", "to", "account"));
    }

    private final ToolRegistry registry = new ToolRegistry(tools(), JSON);

    @Test
    void oneToolPerNameExactlyThePlansTwelve() {
        assertThat(registry.names()).containsExactlyElementsOf(Arrays.stream(ToolName.values()).map(ToolName::wire)
                .toList());
        assertThat(registry.names()).containsExactlyElementsOf(PLAN.keySet());
        assertThat(registry.find("get_trends")).isPresent();
        assertThat(registry.find("dismiss_alert")).isEmpty();
        assertThat(registry.find("GET_TRENDS")).as("never matched loosely").isEmpty();
        assertThat(registry.find("get_trends ")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    @Test
    void eachToolHasThePlansParameters() {
        for (ToolRegistry.Definition definition : registry.definitions()) {
            JsonNode schema = JSON.readTree(definition.inputSchema());
            List<String> params = new ArrayList<>();
            Set<String> required = Set.copyOf(schema.get("required").valueStream().map(JsonNode::stringValue)
                    .toList());
            schema.get("properties").propertyNames().forEach(p -> params.add(required.contains(p) ? p + "!" : p));
            assertThat(params).as(definition.name()).containsExactlyElementsOf(PLAN.get(definition.name()));
        }
    }

    @Test
    void schemasAreClosedAndBounded() {
        for (ToolRegistry.Definition definition : registry.definitions()) {
            JsonNode schema = JSON.readTree(definition.inputSchema());
            assertThat(schema.get("type").stringValue()).isEqualTo("object");
            assertThat(schema.get("additionalProperties").booleanValue()).as(definition.name()).isFalse();
            assertThat(definition.description()).as(definition.name()).isNotBlank().hasSizeLessThan(700);
            schema.get("properties").properties().forEach(e -> {
                JsonNode p = e.getValue();
                String where = definition.name() + "." + e.getKey();
                assertThat(p.get("description").stringValue()).as(where).isNotBlank();
                switch (p.get("type").stringValue()) {
                    case "integer" -> assertThat(p.has("minimum") && p.has("maximum")).as(where).isTrue();
                    case "object" -> assertThat(p.get("additionalProperties").booleanValue()).as(where).isFalse();
                    case "string" -> assertThat(p.has("enum") || p.has("pattern") || p.has("maxLength")).as(where)
                            .isTrue();
                    case "boolean" -> { }
                    case "array" -> assertThat(p.has("minItems") && p.has("maxItems")
                            && p.get("items").has("maxLength")).as(where).isTrue();
                    default -> throw new AssertionError(where);
                }
            });
        }
    }

    @Test
    void limitsAreThePlansCaps() {
        Map<String, Integer> caps = Map.of("get_top_merchants", 20, "list_transactions", 25,
                "get_recurring_charges", 25, "get_alerts", 20, "search_transactions", 10);
        caps.forEach((tool, cap) -> {
            JsonNode limit = JSON.readTree(definition(tool).inputSchema()).get("properties").get("limit");
            assertThat(limit.get("minimum").intValue()).isEqualTo(1);
            assertThat(limit.get("maximum").intValue()).as(tool).isEqualTo(cap);
        });
    }

    @Test
    void oneSchemaInFull() {
        assertThat(JSON.readTree(definition("get_month_totals").inputSchema())).isEqualTo(JSON.readTree("""
                {"type": "object",
                 "properties": {
                   "month": {"type": "string", "pattern": "^\\\\d{4}-(0[1-9]|1[0-2])$",
                             "description": "One month, yyyy-MM: \\"2026-08\\" for August 2026. The same as from and to both that month. Not with from or to."},
                   "from": {"type": "string", "pattern": "^\\\\d{4}-(0[1-9]|1[0-2])$",
                            "description": "First month of a range, yyyy-MM: \\"2026-06\\" for June 2026. Default: 6 months ending at `to`; give it to be sure. Not with month."},
                   "to": {"type": "string", "pattern": "^\\\\d{4}-(0[1-9]|1[0-2])$",
                          "description": "Last month of a range, yyyy-MM: \\"2026-08\\" for August 2026. Default: the latest month with data (latestMonth in the context block). At most 24 months from `from` to `to`, both included. Not with month."},
                   "account": {"type": "string", "maxLength": 20,
                               "description": "Only this account, by its alias from the context block (CHECKING-1). Default: every account."}},
                 "required": [],
                 "additionalProperties": false}"""));
        JsonNode measure = JSON.readTree(definition("compare_periods").inputSchema()).get("properties").get("measure");
        assertThat(measure.get("enum").valueStream().map(JsonNode::stringValue)).containsExactly("SPENDING", "INCOME",
                "NET", "CATEGORY_SPENT", "CATEGORY_RECEIVED");
    }

    /**
     * Step 8b: a turn's definitions list the data's category names as the enum of every category parameter, in the
     * context block's order; nothing else changes. Without names, the parameter stays a bounded string.
     */
    @Test
    void aTurnsDefinitionsListTheCategoryNames() {
        List<String> names = List.of("Dining", "Groceries", "Refunds & Rebates");
        List<ToolRegistry.Definition> turn = registry.definitions(names);
        assertThat(registry.definitions(List.of("Dining", "Groceries", "Refunds & Rebates"))).isSameAs(turn);
        int withCategory = 0;
        for (int i = 0; i < turn.size(); i++) {
            JsonNode plain = JSON.readTree(registry.definitions().get(i).inputSchema());
            JsonNode listed = JSON.readTree(turn.get(i).inputSchema());
            JsonNode category = listed.get("properties").get("category");
            if (category != null) {
                withCategory++;
                assertThat(category.get("enum").valueStream().map(JsonNode::stringValue)).containsExactlyElementsOf(names);
                assertThat(category.has("maxLength")).isFalse();
                assertThat(plain.get("properties").get("category").get("maxLength").intValue()).isEqualTo(100);
                ((tools.jackson.databind.node.ObjectNode) listed.get("properties")).remove("category");
                ((tools.jackson.databind.node.ObjectNode) plain.get("properties")).remove("category");
            }
            assertThat(listed).as(turn.get(i).name()).isEqualTo(plain);
        }
        assertThat(withCategory).as("get_category_totals, get_top_merchants, get_trends, compare_periods, "
                + "list_transactions").isEqualTo(5);
        assertThat(registry.definitions(List.of())).isSameAs(registry.definitions());
    }

    /** §3.2: a search is 2 to 60 characters; a merchant total takes 1 to 10 names, each as results show it. */
    @Test
    void theSearchToolsSchemas() {
        JsonNode query = JSON.readTree(definition("search_transactions").inputSchema()).get("properties").get("query");
        assertThat(query.get("minLength").intValue()).isEqualTo(2);
        assertThat(query.get("maxLength").intValue()).isEqualTo(60);
        JsonNode merchants = JSON.readTree(definition("get_merchant_totals").inputSchema()).get("properties")
                .get("merchants");
        assertThat(merchants.get("type").stringValue()).isEqualTo("array");
        assertThat(merchants.get("minItems").intValue()).isEqualTo(1);
        assertThat(merchants.get("maxItems").intValue()).isEqualTo(10);
        assertThat(merchants.get("items").get("type").stringValue()).isEqualTo("string");
        assertThat(merchants.get("items").get("maxLength").intValue()).isEqualTo(100);
    }

    @Test
    void theApplicationsRegistryNeedsEveryToolOnce() {
        List<ChatTool> missing = new ArrayList<>(tools());
        missing.removeLast();
        assertThatThrownBy(() -> new ToolRegistry(missing, JSON)).hasMessageContaining("get_merchant_totals");
        List<ChatTool> twice = new ArrayList<>(tools());
        twice.add(new TrendsTool(null));
        assertThatThrownBy(() -> new ToolRegistry(twice, JSON)).hasMessageContaining("two chat tools named get_trends");
        assertThat(ToolRegistry.of(List.of(new TrendsTool(null)), JSON).names()).containsExactly("get_trends");
    }

    private ToolRegistry.Definition definition(String name) {
        return registry.definitions().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    }
}
