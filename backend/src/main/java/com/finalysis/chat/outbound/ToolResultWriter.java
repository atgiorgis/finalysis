package com.finalysis.chat.outbound;

import com.finalysis.chat.tool.view.DataNotesView;
import com.finalysis.chat.tool.view.ToolErrorView;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes a tool result as the JSON the model receives (docs/plans/phase-6.md §3.1, §12.1). It takes only what
 * {@link ChatPayloadGuard} returned, so nothing unchecked can be written:
 * <pre>{"result": {…the view…}, "dataNotes": [{type, account, month}], "shortened": true}</pre>
 * {@code dataNotes} is left out when empty, {@code shortened} unless rows were cut. A result over {@value #MAX_BYTES}
 * bytes is cut to fit: one row at a time from the longest list, the oldest first in a list ordered by month, otherwise the
 * last row. Cutting only removes checked rows; it never adds or changes a value.
 */
@Component
public class ToolResultWriter {

    /** The most JSON one tool result may send (§3.1). */
    public static final int MAX_BYTES = 8 * 1024;

    private final JsonMapper json;

    public ToolResultWriter(JsonMapper json) {
        this.json = json;
    }

    /** The JSON sent, and whether rows were cut to fit. */
    public record Written(String json, boolean shortened) {
    }

    /** A result, with its data notes when its view doesn't carry its own (null). */
    public Written result(ChatPayloadGuard.Checked<? extends Record> view,
                          ChatPayloadGuard.Checked<DataNotesView> notes) {
        ObjectNode envelope = json.createObjectNode();
        envelope.set("result", json.valueToTree(view.view()));
        if (notes != null && !notes.view().notes().isEmpty()) {
            envelope.set("dataNotes", json.valueToTree(notes.view().notes()));
        }
        boolean shortened = false;
        while (size(envelope) > MAX_BYTES) {
            ArrayNode longest = longest(envelope.get("result"), null);
            if (longest == null || longest.isEmpty()) {
                break;
            }
            longest.remove(isMonths(longest) ? 0 : longest.size() - 1);
            shortened = true;
        }
        if (shortened) {
            envelope.put("shortened", true);
        }
        return new Written(json.writeValueAsString(envelope), shortened);
    }

    /** An error result. */
    public String error(ChatPayloadGuard.Checked<ToolErrorView> error) {
        return json.writeValueAsString(error.view());
    }

    private int size(JsonNode node) {
        return json.writeValueAsString(node).getBytes(StandardCharsets.UTF_8).length;
    }

    /** The longest non-empty array in the tree; a tie goes to the one found last, the deeper, so rows outlast their cells. */
    private static ArrayNode longest(JsonNode node, ArrayNode best) {
        if (node == null) {
            return best;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            if (!array.isEmpty() && (best == null || array.size() >= best.size())) {
                best = array;
            }
        }
        for (JsonNode child : node.values()) {
            best = longest(child, best);
        }
        return best;
    }

    /** A list ordered by month, oldest first: its oldest row is the least useful. */
    private static boolean isMonths(ArrayNode array) {
        JsonNode first = array.get(0).get("month");
        JsonNode last = array.get(array.size() - 1).get("month");
        return first != null && last != null && first.isString() && last.isString()
                && first.stringValue().compareTo(last.stringValue()) < 0;
    }
}
