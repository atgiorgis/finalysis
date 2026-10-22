package com.finalysis.chat.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.chat.LimitHit;
import com.finalysis.chat.tool.ToolName;
import com.finalysis.chat.tool.view.FlowView;
import com.finalysis.chat.tool.view.TransactionsView;
import com.finalysis.chat.tool.view.TxnView;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The Java-built fallback (docs/plans/phase-6.md §5.5, §18 decision 4). Every tool's template is also run on real
 * results in {@code CitationTest}.
 */
class FallbackAnswerTest {

    @Test
    void oneLinePerRowWithItsRef() {
        String fallback = FallbackAnswer.render(Evidence.shoppingSpike().build());

        assertThat(fallback).isEqualTo("""
                I couldn't confirm every figure in my answer, so here is what the data returned:
                - Category spike · August 2026 · Shopping · observed 1,183.67 · threshold 366.63 [a1]
                - Shopping · August 2026 · spent 1,183.67 · previous month 301.20 · change from previous month 882.47 \
                (293.0%) · three-month average 244.42, difference from it 939.25 · six-month average 230.10, \
                difference from it 953.57 [c1]""");
    }

    /** Nothing in it was computed or invented: it passes the same grounding check as a model's answer would. */
    @Test
    void theFallbackIsItselfGrounded() {
        Evidence e = Evidence.shoppingSpike();

        AnswerCheck check = e.check(FallbackAnswer.render(e.build()));

        assertThat(check.grounded()).isTrue();
        assertThat(check.dropped()).isZero();
        assertThat(check.markers()).extracting(CitationMarkers.Marker::ref).containsExactly("a1", "c1");
    }

    @Test
    void withoutResultsItSaysSo() {
        assertThat(FallbackAnswer.render(new Evidence().build())).isEqualTo("I can't answer that from your data.");
        assertThat(FallbackAnswer.render(new Evidence().error(ToolName.GET_TRENDS, "{}").build()))
                .isEqualTo("I can't answer that from your data.");
    }

    /** Step 8d: a turn a limit stopped before any data says which limit, not that the data can't answer. */
    @ParameterizedTest
    @EnumSource(LimitHit.class)
    void aLimitBeforeAnyDataSaysWhichLimit(LimitHit limit) {
        String shown = FallbackAnswer.render(new Evidence().build(), limit);

        assertThat(shown).startsWith("I stopped before getting any data from your records: this question reached the ")
                .doesNotContain("can't answer");
        assertThat(FigureExtractor.extract(shown)).isEmpty();
        assertThat(FallbackAnswer.render(new Evidence().build(), LimitHit.COST)).contains("the cost limit");
        assertThat(FallbackAnswer.render(new Evidence().build(), LimitHit.TIME)).contains("the time limit");
        // with data, the rows are shown as before, whatever the limit
        assertThat(FallbackAnswer.render(Evidence.shoppingSpike().build(), limit))
                .isEqualTo(FallbackAnswer.render(Evidence.shoppingSpike().build()));
    }

    @Test
    void longResultsAreCut() {
        Evidence e = new Evidence();
        List<TxnView> txns = IntStream.rangeClosed(1, 24).mapToObj(i -> new TxnView(e.txn(i), "2026-03-01",
                "-1.00", null, null, "EXPENSE", "CHECKING-1")).toList();
        e.result(ToolName.LIST_TRANSACTIONS, "{\"limit\": 24}", new TransactionsView(e.cell("all"), "2026-03",
                "2026-03", new FlowView("-24.00", "0.00", "-24.00", 24), txns, false));

        List<String> lines = FallbackAnswer.render(e.build()).lines().toList();

        assertThat(lines).hasSize(1 + FallbackAnswer.MAX_LINES + 1);
        assertThat(lines.get(1)).isEqualTo("- Transactions · March 2026 · money out 24.00 · money in 0.00 · "
                + "24 transactions [c1]");
        assertThat(lines.get(2)).isEqualTo("- 2026-03-01 · (no merchant) · -1.00 · CHECKING-1 [t1]");
        assertThat(lines.getLast()).isEqualTo("- (5 more rows not shown)");
    }
}
