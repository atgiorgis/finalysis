package com.finalysis.chat.grounding;

import com.finalysis.ai.outbound.OutboundPayloadGuard;
import com.finalysis.chat.ToolCall;
import com.finalysis.chat.ToolOutcome;
import com.finalysis.chat.ToolStatus;
import com.finalysis.chat.outbound.ChatPayloadGuard;
import com.finalysis.chat.tool.RefRegistry;
import com.finalysis.chat.tool.ToolName;
import com.finalysis.chat.tool.view.AlertsView;
import com.finalysis.chat.tool.view.AverageView;
import com.finalysis.chat.tool.view.TrendsView;
import com.finalysis.chat.tool.view.TxnView;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Hand-written turn evidence for the grounding unit tests: views written as the executor's envelope, refs issued in a
 * real {@link RefRegistry}. No database, no model.
 */
final class Evidence {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final ChatPayloadGuard GUARD = new ChatPayloadGuard(new OutboundPayloadGuard());

    final RefRegistry refs = new RefRegistry();
    private final List<TurnEvidence.Result> results = new ArrayList<>();
    private String question = "";
    private final List<String> names = new ArrayList<>();

    Evidence question(String question) {
        this.question = question;
        return this;
    }

    Evidence names(String... names) {
        this.names.addAll(List.of(names));
        return this;
    }

    String cell(String label) {
        return refs.ref(new RefRegistry.Cell(label, List.of(Map.of("label", label)), Map.of()));
    }

    String txn(long id) {
        return refs.ref(new RefRegistry.Txn(id));
    }

    String alert(String key) {
        return refs.ref(new RefRegistry.Alert(key, "CATEGORY_SPIKE", YearMonth.of(2026, 8), List.of()));
    }

    /** A successful result, sent whole. */
    Evidence result(ToolName tool, String arguments, Record view) {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.set("result", JSON.valueToTree(view));
        return sent(tool, arguments, view, JSON.writeValueAsString(envelope));
    }

    /** A successful result whose sent JSON is given (a shortened one). */
    Evidence sent(ToolName tool, String arguments, Record view, String json) {
        results.add(new TurnEvidence.Result(new ToolCall(tool.wire(), arguments),
                new ToolOutcome(tool, ToolStatus.OK, json, view, 1, 1, List.of(), false)));
        return this;
    }

    /** A failed call: its error JSON and arguments ground nothing. */
    Evidence error(ToolName tool, String arguments) {
        results.add(new TurnEvidence.Result(new ToolCall(tool.wire(), arguments),
                new ToolOutcome(tool, ToolStatus.INVALID_ARGUMENTS,
                        "{\"error\": \"INVALID_ARGUMENTS\", \"message\": \"is outside the minimum and maximum\"}",
                        null, 0, 1, List.of(), false)));
        return this;
    }

    TurnEvidence build() {
        return new TurnEvidence(question, results, refs, names);
    }

    AnswerCheck check(String answer) {
        return new AnswerGrounding(GUARD).check(answer, build());
    }

    /**
     * The plan's worked example (§5.4): "Is my shopping unusual this summer?" with {@code get_alerts} (the August
     * Shopping spike, its $899.99 purchase) and {@code get_trends(month=2026-08, category=Shopping)}.
     */
    static Evidence shoppingSpike() {
        Evidence e = new Evidence().question("Is my shopping unusual this summer?");
        String a1 = e.alert("category-spike.2026-08.4b1f0c9a7e2d3851");
        String t1 = e.txn(412);
        AlertsView alerts = new AlertsView("2026-03", "2026-08", List.of(new AlertsView.Item(a1, "CATEGORY_SPIKE",
                "2026-08", new AlertsView.Basis(new AlertsView.Quantity("MONEY", "1183.67"),
                        new AlertsView.Quantity("MONEY", "244.42"), new AlertsView.Quantity("MONEY", "366.63"),
                        "2026-08-01", "2026-08-31", List.of(new AlertsView.Setting("min-increase-percent",
                                new AlertsView.Quantity("PERCENT", "50.0")))),
                new AlertsView.Details("CATEGORY_SPIKE", "Shopping", null, null, "1183.67",
                        new AverageView("244.42", 3, 3), "939.25", "384.3", null, null, null, null, null, null, null,
                        null, null, null, null, null),
                List.of(new TxnView(t1, "2026-08-22", "-899.99", "BEST BUY", "Shopping", "EXPENSE", "CARD-1")),
                List.of(), false)), List.of(new AlertsView.Count("CATEGORY_SPIKE", 1)), false);
        String c1 = e.cell("Shopping · 2026-08");
        TrendsView trends = new TrendsView("2026-08", null, "Shopping", List.of(new TrendsView.Row(c1, "CATEGORY",
                "EXPENSE", "Shopping", "SPENT", "1183.67", "301.20", "882.47", "293.0", new AverageView("244.42", 3, 3),
                new AverageView("230.10", 6, 6), "198.00", "COMPLETE")));
        return e.result(ToolName.GET_ALERTS, "{\"limit\": 5}", alerts)
                .result(ToolName.GET_TRENDS, "{\"month\": \"2026-08\", \"category\": \"Shopping\"}", trends);
    }
}
