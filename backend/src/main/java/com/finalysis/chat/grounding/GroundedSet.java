package com.finalysis.chat.grounding;

import com.finalysis.chat.ToolStatus;
import com.finalysis.chat.outbound.ChatPayloadGuard;
import com.finalysis.chat.tool.view.OutboundKind;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The figures an answer may use (docs/plans/phase-6.md §5.3), built from the turn's tool results <em>as they were
 * sent</em>: the outbound JSON, read with its view's field kinds, so nothing the model didn't see can ground a figure
 * and a number inside a merchant name never does (only money, percent, ratio, number and count fields are read).
 * Each value points at the ref of the nearest object around it that has one, and at its path inside that object.
 * <ul>
 *   <li>money fields (and alert quantities in money) → magnitude at scale 2;</li>
 *   <li>ratios ("13.55 times the median") → kept apart: they match a two-decimal figure exactly, never rounded;</li>
 *   <li>percent fields → magnitude at scale 1;</li>
 *   <li>counts (and quantities in counts or days) → integers;</li>
 *   <li>for counts only: the whole numbers in the user's question and in the arguments of this turn's successful tool
 *       calls ("your top 5 merchants"), which point at no ref.</li>
 * </ul>
 * Error results ground nothing.
 */
public final class GroundedSet {

    public enum Kind { MONEY, RATIO, PERCENT, COUNT }

    /**
     * One value of the set.
     *
     * @param returned the value as the result wrote it, signed ({@code -1183.67}); null for the question's numbers
     * @param ref      the ref of the object around it, or null (the question, the arguments, an unreferenced total)
     * @param field    its path inside that object ({@code spending}, {@code months[2026-08].net}), or where it came from
     * @param result   the index of its tool result in the turn, or -1
     */
    public record Value(Kind kind, BigDecimal magnitude, String returned, String ref, String field, int result) {
    }

    static final String QUESTION = "(question)";
    static final String ARGUMENTS = "(arguments)";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern WHOLE = Pattern.compile("\\d+");

    private final List<Value> values;
    private final Set<String> labels;

    private GroundedSet(List<Value> values, Set<String> labels) {
        this.values = List.copyOf(values);
        this.labels = Set.copyOf(labels);
    }

    public static GroundedSet of(TurnEvidence evidence, ChatPayloadGuard guard) {
        List<Value> values = new ArrayList<>();
        Set<String> labels = new LinkedHashSet<>(evidence.names());
        List<TurnEvidence.Result> results = evidence.results();
        for (int i = 0; i < results.size(); i++) {
            TurnEvidence.Result r = results.get(i);
            if (r.outcome().status() != ToolStatus.OK || r.outcome().view() == null) {
                continue;
            }
            JsonNode sent = JSON.readTree(r.outcome().json()).get("result");
            new Walk(guard, values, labels, i).object(sent, r.outcome().view().getClass(), null, "");
            arguments(r.call().arguments(), values);
        }
        Matcher m = WHOLE.matcher(evidence.question() == null ? "" : evidence.question());
        while (m.find()) {
            values.add(new Value(Kind.COUNT, new BigDecimal(new BigInteger(m.group())), null, null, QUESTION, -1));
        }
        return new GroundedSet(values, labels);
    }

    public List<Value> values() {
        return values;
    }

    /** The labels this turn sent (merchants, categories, aliases) and the names the caller gave. */
    public Set<String> labels() {
        return labels;
    }

    /** The whole numbers among a call's arguments, at any depth. */
    private static void arguments(String text, List<Value> values) {
        if (text == null || text.isBlank()) {
            return;
        }
        JsonNode arguments;
        try {
            arguments = JSON.readTree(text);
        } catch (JacksonException e) {
            return;
        }
        collectIntegers(arguments, values);
    }

    private static void collectIntegers(JsonNode node, List<Value> values) {
        if (node.isIntegralNumber()) {
            values.add(new Value(Kind.COUNT, new BigDecimal(node.bigIntegerValue()).abs(), node.asString(), null,
                    ARGUMENTS, -1));
        }
        for (JsonNode child : node.values()) {
            collectIntegers(child, values);
        }
    }

    /** One result's walk: the JSON beside the view type's layout. */
    private record Walk(ChatPayloadGuard guard, List<Value> values, Set<String> labels, int result) {

        void object(JsonNode node, Class<?> type, String outerRef, String outerPath) {
            if (node == null || !node.isObject()) {
                return;
            }
            Map<String, ChatPayloadGuard.Field> fields = guard.classify(type).stream()
                    .collect(Collectors.toMap(f -> f.component().getName(), Function.identity()));
            ChatPayloadGuard.Field refField = fields.get("ref");
            boolean own = refField != null && refField.kind() == OutboundKind.REF && node.path("ref").isString();
            String ref = own ? node.get("ref").stringValue() : outerRef;
            String path = own ? "" : outerPath;
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                ChatPayloadGuard.Field field = fields.get(property.getKey());
                if (field == null) {
                    continue;
                }
                String at = path + property.getKey();
                JsonNode value = property.getValue();
                if (field.kind() != null) {
                    if (value.isArray()) {
                        value.values().forEach(v -> leaf(field.kind(), v, node, ref, at));
                    } else {
                        leaf(field.kind(), value, node, ref, at);
                    }
                } else if (value.isArray()) {
                    int index = 0;
                    for (JsonNode element : value.values()) {
                        String key = element.path("month").isString() ? element.get("month").stringValue()
                                : Integer.toString(index);
                        object(element, field.nestedRecord(), ref, at + "[" + key + "].");
                        index++;
                    }
                } else {
                    object(value, field.nestedRecord(), ref, at + ".");
                }
            }
        }

        private void leaf(OutboundKind kind, JsonNode value, JsonNode parent, String ref, String path) {
            if (value == null || value.isNull()) {
                return;
            }
            switch (kind) {
                case MONEY -> add(Kind.MONEY, value, ref, path);
                case RATIO -> add(Kind.RATIO, value, ref, path);
                case PERCENT -> add(Kind.PERCENT, value, ref, path);
                case COUNT -> add(Kind.COUNT, value, ref, path);
                case NUMBER -> {
                    // an alert quantity: its unit is the sibling field
                    switch (parent.path("unit").asString("")) {
                        case "MONEY" -> add(Kind.MONEY, value, ref, path);
                        case "RATIO" -> add(Kind.RATIO, value, ref, path);
                        case "PERCENT" -> add(Kind.PERCENT, value, ref, path);
                        case "COUNT", "DAYS" -> add(Kind.COUNT, value, ref, path);
                        default -> { }
                    }
                }
                case MERCHANT, CATEGORY, ALIAS -> labels.add(value.asString());
                default -> { }
            }
        }

        private void add(Kind kind, JsonNode value, String ref, String path) {
            String text = value.asString();
            BigDecimal magnitude;
            try {
                magnitude = new BigDecimal(text).abs();
            } catch (NumberFormatException e) {
                return;
            }
            values.add(new Value(kind, magnitude, text, ref, path, result));
        }
    }
}
