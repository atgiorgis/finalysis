package com.finalysis.chat.tool;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The fixed list of chat tools (docs/plans/phase-6.md §3, §9): built once at startup, exactly one tool per
 * {@link ToolName}, each with its closed JSON schema. A name not in it gets an error result, so the model can't reach a
 * writer by naming one.
 */
@Component
public class ToolRegistry {

    /** A tool as the model sees it: its name, description and input schema (JSON). */
    public record Definition(String name, String description, String inputSchema) {
    }

    private final Map<ToolName, ChatTool> tools;
    private final JsonMapper json;
    private final List<Definition> definitions;
    /** The last category list's definitions: categories rarely change, so one entry serves every turn. */
    private volatile Map.Entry<List<String>, List<Definition>> withCategories = Map.entry(List.of(), List.of());

    /** The application's registry: every name has its tool. */
    @Autowired
    public ToolRegistry(List<ChatTool> tools, JsonMapper json) {
        this(tools, json, true);
    }

    private ToolRegistry(List<ChatTool> tools, JsonMapper json, boolean complete) {
        Map<ToolName, ChatTool> byName = new EnumMap<>(ToolName.class);
        for (ChatTool tool : tools) {
            if (byName.put(tool.name(), tool) != null) {
                throw new IllegalStateException("two chat tools named " + tool.name().wire());
            }
            checkParams(tool);
        }
        if (complete && byName.size() != ToolName.values().length) {
            throw new IllegalStateException("chat tools missing: " + Arrays.stream(ToolName.values())
                    .filter(name -> !byName.containsKey(name)).map(ToolName::wire).toList());
        }
        this.tools = Collections.unmodifiableMap(byName);
        this.json = json;
        this.definitions = build(List.of());
    }

    /** A registry of just these tools, for tests that need one the application doesn't have. */
    public static ToolRegistry of(List<ChatTool> tools, JsonMapper json) {
        return new ToolRegistry(tools, json, false);
    }

    /** The tool a model's name means; never matched loosely. */
    public Optional<ChatTool> find(String wireName) {
        return ToolName.fromWire(wireName).map(tools::get);
    }

    /** The definitions with free-text category parameters (no enum): for tests and size estimates without data. */
    public List<Definition> definitions() {
        return definitions;
    }

    /**
     * The definitions a turn sends: every category parameter lists the data's category names as an {@code enum}
     * (step 8b), so the model picks a valid name instead of guessing one ("dining", "Refunds"). Java still checks the
     * name ({@link ToolParam.Category}); the enum only helps.
     *
     * @param categories the context block's category names, in its order
     */
    public List<Definition> definitions(List<String> categories) {
        if (categories.isEmpty()) {
            return definitions;
        }
        Map.Entry<List<String>, List<Definition>> cached = withCategories;
        if (!cached.getKey().equals(categories)) {
            cached = Map.entry(List.copyOf(categories), build(categories));
            withCategories = cached;
        }
        return cached.getValue();
    }

    private List<Definition> build(List<String> categories) {
        return tools.values().stream()
                .map(tool -> new Definition(tool.name().wire(), tool.description(), schema(tool, json, categories)))
                .toList();
    }

    public List<String> names() {
        return definitions.stream().map(Definition::name).toList();
    }

    /**
     * The closed schema: an object with exactly these properties ({@code additionalProperties: false}), the required
     * ones listed, enums, patterns and bounds from each parameter's type.
     */
    static String schema(ChatTool tool, JsonMapper json) {
        return schema(tool, json, List.of());
    }

    static String schema(ChatTool tool, JsonMapper json, List<String> categories) {
        ObjectNode schema = json.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (ToolParam param : tool.params()) {
            ObjectNode property = properties.putObject(param.name());
            param.type().schema(property);
            if (param.type() instanceof ToolParam.Category && !categories.isEmpty()) {
                property.remove("maxLength");
                ArrayNode names = property.putArray("enum");
                categories.forEach(names::add);
            }
            property.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }
        schema.put("additionalProperties", false);
        return json.writeValueAsString(schema);
    }

    private static void checkParams(ChatTool tool) {
        List<String> names = tool.params().stream().map(ToolParam::name).toList();
        if (names.stream().distinct().count() != names.size()
                || !names.stream().allMatch(n -> n.matches("[a-z][A-Za-z]{0,30}"))) {
            throw new IllegalStateException(tool.name().wire() + " has duplicate or malformed parameter names");
        }
    }
}
