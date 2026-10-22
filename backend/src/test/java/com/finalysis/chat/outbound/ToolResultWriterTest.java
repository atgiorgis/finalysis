package com.finalysis.chat.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.ai.outbound.OutboundPayloadGuard;
import com.finalysis.chat.tool.FieldProblem;
import com.finalysis.chat.tool.RefRegistry;
import com.finalysis.chat.tool.ToolProblem;
import com.finalysis.chat.tool.view.CategoryTotalsView;
import com.finalysis.chat.tool.view.DataNotesView;
import com.finalysis.chat.tool.view.FlagView;
import com.finalysis.chat.tool.view.FlowView;
import com.finalysis.chat.tool.view.MonthCoverageView;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The JSON a tool result is sent as (docs/plans/phase-6.md §3.1): the envelope, data notes, the 8 KB cap. */
class ToolResultWriterTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final ChatPayloadGuard guard = new ChatPayloadGuard(new OutboundPayloadGuard());
    private final ToolResultWriter writer = new ToolResultWriter(json);

    @Test
    void aSmallResultIsSentWholeWithItsNotes() {
        OutboundTurn turn = OutboundTurns.turn();
        CategoryTotalsView view = view(turn, 2, 2);

        ToolResultWriter.Written written = writer.result(guard.check(view, turn),
                guard.check(new DataNotesView(List.of(new FlagView("PARTIAL_COVERAGE", "CARD-1", "2026-03"))), turn));

        JsonNode sent = json.readTree(written.json());
        assertThat(written.shortened()).isFalse();
        assertThat(sent.propertyNames()).containsExactly("result", "dataNotes");
        assertThat(sent.get("result")).isEqualTo(json.valueToTree(view));
        assertThat(sent.get("dataNotes").get(0).get("account").stringValue()).isEqualTo("CARD-1");
    }

    @Test
    void noNotesNoNotesField() {
        OutboundTurn turn = OutboundTurns.turn();

        String sent = writer.result(guard.check(view(turn, 1, 1), turn),
                guard.check(new DataNotesView(List.of()), turn)).json();

        assertThat(json.readTree(sent).propertyNames()).containsExactly("result");
        assertThat(json.readTree(writer.result(guard.check(view(turn, 1, 1), turn), null).json()).propertyNames())
                .containsExactly("result");
    }

    @Test
    void aLargeResultIsCutToFitOldestMonthsFirst() {
        OutboundTurn turn = OutboundTurns.turn();
        CategoryTotalsView view = view(turn, 6, 24);
        assertThat(json.writeValueAsString(view).length()).isGreaterThan(ToolResultWriter.MAX_BYTES);

        ToolResultWriter.Written written = writer.result(guard.check(view, turn), null);

        assertThat(written.shortened()).isTrue();
        assertThat(written.json().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(ToolResultWriter.MAX_BYTES);
        JsonNode sent = json.readTree(written.json());
        assertThat(sent.get("shortened").booleanValue()).isTrue();
        JsonNode rows = sent.get("result").get("rows");
        assertThat(rows.size()).as("rows and their totals are kept while their month lists are longer").isEqualTo(6);
        JsonNode months = rows.get(0).get("months");
        assertThat(months.get(months.size() - 1).get("month").stringValue()).as("the latest month stays")
                .isEqualTo("2026-12");
        assertThat(months.size()).isLessThan(24);
        // Nothing is changed or added: every kept cell is one of the view's.
        assertThat(json.valueToTree(view).toString()).contains(months.get(0).toString());
    }

    @Test
    void anErrorIsItsCheckedView() {
        OutboundTurn turn = OutboundTurns.turn();
        OutboundProjector projector = new OutboundProjector();

        String sent = writer.error(guard.check(projector.error("INVALID_ARGUMENTS", ToolProblem.INVALID_ARGUMENTS,
                List.of(new FieldProblem("category", ToolProblem.NOT_A_CATEGORY),
                        new FieldProblem("uncategorized", ToolProblem.CONFLICT, "category")), turn), turn));

        assertThat(json.readTree(sent)).isEqualTo(json.readTree("""
                {"error": "INVALID_ARGUMENTS",
                 "message": "one or more arguments are invalid; fix them and call again, or answer without this tool",
                 "fields": [{"name": "category",
                             "message": "not a category name; use one of categories exactly as written",
                             "categories": ["Dining", "Groceries"]},
                            {"name": "uncategorized",
                             "message": "can't be combined with the parameter named in conflictsWith",
                             "conflictsWith": "category"}]}"""));
    }

    /** Rows × months of Dining cells, January 2025 + … */
    private static CategoryTotalsView view(OutboundTurn turn, int rows, int months) {
        List<String> monthNames = IntStream.range(0, months)
                .mapToObj(i -> YearMonth.of(2026, 12).minusMonths(months - 1 - i).toString()).toList();
        FlowView flow = new FlowView("-123.45", "10.00", "-113.45", 7);
        return new CategoryTotalsView(monthNames.getFirst(), monthNames.getLast(), null,
                monthNames.stream().map(m -> new MonthCoverageView(m, "COMPLETE")).toList(), null,
                IntStream.range(0, rows).mapToObj(r -> new CategoryTotalsView.Row(
                        turn.refs().ref(new RefRegistry.Cell("row " + r, List.of(Map.of("r", "" + r)), Map.of())), "EXPENSE",
                        "Dining", flow, null, monthNames.stream().map(m -> new CategoryTotalsView.Cell(m, "-123.45",
                                "10.00", "-113.45", 7, null)).toList())).toList());
    }
}
