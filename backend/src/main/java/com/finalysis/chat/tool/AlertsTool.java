package com.finalysis.chat.tool;

import static com.finalysis.chat.tool.Params.ACCOUNT;
import static com.finalysis.chat.tool.Params.MONTH;
import static com.finalysis.chat.tool.Params.TO;

import com.finalysis.alert.AlertQueryService;
import com.finalysis.alert.AlertType;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code get_alerts} over {@code AlertQueryService.alerts} (docs/plans/phase-6.md §3.2). Without a period it covers
 * every month with data ({@link DataMonths}, step 8b): the service's 3-month default hid older alerts.
 */
@Component
class AlertsTool implements ChatTool {

    static final int MAX_LIMIT = 20;

    private static final ToolParam TYPE = ToolParam.optional("type", new ToolParam.Choice(AlertType.class),
            "Only this kind of alert. Default: every kind.");
    private static final ToolParam INCLUDE_DISMISSED = ToolParam.optional("includeDismissed", new ToolParam.Flag(),
            "true: also alerts the user dismissed (marked dismissed). Default false.");

    private final AlertQueryService alerts;
    private final DataMonths dataMonths;

    AlertsTool(AlertQueryService alerts, DataMonths dataMonths) {
        this.alerts = alerts;
        this.dataMonths = dataMonths;
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
                + "transactions and data flags; counts per type cover every match. Without month or from, it covers "
                + "every month with data (at most " + ToolParam.MAX_MONTHS + "); from and to in the result say which "
                + "months it covered.";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(MONTH, Params.ALL_DATA_FROM, TO, TYPE, ACCOUNT, INCLUDE_DISMISSED, Params.limit(MAX_LIMIT));
    }

    @Override
    public List<FieldProblem> check(ToolArguments arguments) {
        return arguments.rangeProblems();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        DataMonths.Range range = dataMonths.resolve(arguments);
        return new ToolRead.Alerts(alerts.alerts(range.from(), range.to(),
                arguments.choice("type", AlertType.class), arguments.id("account"),
                arguments.flag("includeDismissed", false), arguments.number("limit")));
    }
}
