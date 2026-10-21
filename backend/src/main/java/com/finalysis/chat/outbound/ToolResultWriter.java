package com.finalysis.chat.outbound;

import com.finalysis.chat.tool.view.ContextBlockView;
import com.finalysis.chat.tool.view.DataNotesView;
import com.finalysis.chat.tool.view.HistoryView;
import com.finalysis.chat.tool.view.MonthlyRecordsView;
import com.finalysis.chat.tool.view.ToolErrorView;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes what the model receives as JSON (docs/plans/phase-6.md §3.1, §12.1): tool results, the context block and
 * question of every round, the conversation's memory (§7) and the monthly summary's records (§11.1). It takes only what {@link ChatPayloadGuard} returned, so nothing unchecked can be written:
 * <pre>{"result": {…the view…}, "dataNotes": [{type, account, month}], "shortened": true}</pre>
 * {@code dataNotes} is left out when empty, {@code shortened} unless rows were cut. A result over {@value #MAX_BYTES}
 * bytes is cut to fit: one row at a time from the longest list, the oldest first in a list ordered by month, otherwise the
 * last row. Cutting only removes checked rows; it never adds or changes a value.
 */
@Component
public class ToolResultWriter {

    /** The most JSON one tool result may send (§3.1). */
    public static final int MAX_BYTES = 8 * 1024;
    /** The most JSON the monthly summary's records may send: four results' worth in one block. */
    public static final int MAX_RECORDS_BYTES = 16 * 1024;

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
        return capped(envelope, MAX_BYTES);
    }

    /**
     * The monthly summary's records (§11.1), in the same envelope as a tool result, so grounding reads them the same
     * way; cut to {@value #MAX_RECORDS_BYTES} bytes by the same rule.
     */
    public Written records(ChatPayloadGuard.Checked<MonthlyRecordsView> records) {
        ObjectNode envelope = json.createObjectNode();
        envelope.set("result", json.valueToTree(records.view()));
        return capped(envelope, MAX_RECORDS_BYTES);
    }

    private Written capped(ObjectNode envelope, int maxBytes) {
        boolean shortened = false;
        while (size(envelope) > maxBytes) {
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

    /** The context block (§3.4) as JSON, for the {@code <context>} message. */
    public String context(ChatPayloadGuard.Checked<ContextBlockView> context) {
        return json.writeValueAsString(context.view());
    }

    /** The outbound question as a JSON string (quotes and backslashes escaped), for the {@code <question>} message. */
    public String question(ChatPayloadGuard.Checked<String> question) {
        return json.writeValueAsString(question.view());
    }

    /** The summary of older turns as a JSON string, for the {@code <summary>} message (§7.1). */
    public String summary(ChatPayloadGuard.Checked<String> summary) {
        return json.writeValueAsString(summary.view());
    }

    /**
     * An earlier answer, figures stripped, as the assistant message of the history (§7.1): plain text, as the model
     * wrote it then.
     */
    public String answer(ChatPayloadGuard.Checked<String> answer) {
        return answer.view();
    }

    /** What the summary call folds (§7.2): the previous summary and the folded turns. */
    public String history(ChatPayloadGuard.Checked<HistoryView> history) {
        return json.writeValueAsString(history.view());
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
