package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import java.util.List;

/** {@code get_data_coverage} (§3.2): per alias, covered spans, gaps and flagged statements (no statement id). */
public record CoverageView(@Outbound(DATE) String lastCoveredDay, @Outbound(MONTH) String latestMonth,
                           @Outbound(MONTH) String latestCompleteMonth, List<Account> accounts) {

    public record Account(@Outbound(ALIAS) String account, @Outbound(DATE) String firstDay,
                          @Outbound(DATE) String closedOn, List<Span> covered, List<Span> gaps,
                          List<Flagged> flaggedStatements, @Outbound(FLAG) Boolean truncated) {
    }

    public record Span(@Outbound(DATE) String start, @Outbound(DATE) String end) {
    }

    public record Flagged(@Outbound(ENUM) String flag, @Outbound(DATE) String periodStart,
                          @Outbound(DATE) String periodEnd) {
    }
}
