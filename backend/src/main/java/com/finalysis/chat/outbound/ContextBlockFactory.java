package com.finalysis.chat.outbound;

import com.finalysis.alert.AlertType;
import com.finalysis.analytics.CoverageResponse;
import com.finalysis.analytics.CoverageService;
import com.finalysis.chat.tool.CategoryNamesQuery;
import com.finalysis.chat.tool.view.ContextBlockView;
import java.time.LocalDate;
import java.util.Arrays;
import org.springframework.stereotype.Component;

/**
 * The context block of every round (docs/plans/phase-6.md §3.4): today, the latest and latest complete month, the
 * accounts by alias, the category names and the alert types. "Today" is the caller's: the chat loop's clock (step 5),
 * or the evaluation's pinned date (step 8).
 */
@Component
public class ContextBlockFactory {

    private final CoverageService coverage;

    public ContextBlockFactory(CoverageService coverage) {
        this.coverage = coverage;
    }

    public ContextBlockView build(OutboundTurn turn, LocalDate today) {
        CoverageResponse data = coverage.coverage();
        return new ContextBlockView(today.toString(),
                data.latestMonth() == null ? null : data.latestMonth().toString(),
                data.latestCompleteMonth() == null ? null : data.latestCompleteMonth().toString(),
                turn.aliases().entries().stream()
                        .map(e -> new ContextBlockView.AccountEntry(e.alias(), e.type(), e.closed()))
                        .toList(),
                CategoryNamesQuery.sorted(turn.categories()),
                Arrays.stream(AlertType.values()).map(Enum::name).toList());
    }
}
