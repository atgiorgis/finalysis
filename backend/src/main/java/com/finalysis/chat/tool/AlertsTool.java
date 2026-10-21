package com.finalysis.chat.tool;

import static com.finalysis.chat.tool.Params.ACCOUNT;
import static com.finalysis.chat.tool.Params.TO;

import com.finalysis.alert.AlertQueryService;
import com.finalysis.alert.AlertType;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** {@code get_alerts} over {@code AlertQueryService.alerts} (docs/plans/phase-6.md §3.2). */
@Component
class AlertsTool implements ChatTool {

    static final int MAX_LIMIT = 20;

    private static final ToolParam ALERTS_FROM = ToolParam.optional("from", new ToolParam.Month(),
            "First month, yyyy-MM. Default: 3 months ending at `to`.");
    private static final ToolParam TYPE = ToolParam.optional("type", new ToolParam.Choice(AlertType.class),
            "Only this kind of alert. Default: every kind.");
    private static final ToolParam INCLUDE_DISMISSED = ToolParam.optional("includeDismissed", new ToolParam.Flag(),
            "true: also alerts the user dismissed (marked dismissed). Default false.");

    private final AlertQueryService alerts;

    AlertsTool(AlertQueryService alerts) {
        this.alerts = alerts;
    }

    @Override
    public ToolName name() {
        return ToolName.GET_ALERTS;
    }

    @Override
    public String description() {
        return "Unusual activity, newest month first: category spikes, large transactions, possible duplicates, "
                + "recurring price increases and new recurring charges. Each alert has its month, its basis (observed, "
                + "baseline and threshold with units; amounts are positive magnitudes), typed details, up to 5 "
                + "transactions and data flags; counts per type cover every match.";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(ALERTS_FROM, TO, TYPE, ACCOUNT, INCLUDE_DISMISSED, Params.limit(MAX_LIMIT));
    }

    @Override
    public List<FieldProblem> check(ToolArguments arguments) {
        return arguments.rangeProblems();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        return new ToolRead.Alerts(alerts.alerts(arguments.month("from"), arguments.month("to"),
                arguments.choice("type", AlertType.class), arguments.id("account"),
                arguments.flag("includeDismissed", false), arguments.number("limit")));
    }
}
